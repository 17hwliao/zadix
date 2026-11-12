package com.ahuamiao.adprotection;

import java.util.List;
import java.util.regex.Pattern;

/** A bounded fallback for the version whose opening advertisement can expose ID-less nodes. */
final class NetdiskSplash {
    static final String PACKAGE = "com.baidu.netdisk";
    static final String MAIN = PACKAGE + ".ui.MainActivity";
    static final String RULE = "netdisk-startup-brand";
    static final String AD_CONTAINER = PACKAGE + ":id/fl_ad_container";
    static final String BLOCKED_VIEW = PACKAGE + ":id/dialog_cancel";
    static final int SUPPORTED_VERSION = 4262;
    private static final Pattern SKIP = Pattern.compile("跳过(?:\\h*[0-9]{1,2}(?:\\h*秒)?)?");
    private static final long OPENING_MS = 10_000;

    enum Label { NONE, BRAND_AD, SKIP }
    record Box(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
        boolean contains(Box b) {
            return b.width() > 0 && b.height() > 0
                    && left <= b.left && top <= b.top && right >= b.right && bottom >= b.bottom;
        }
    }
    record Node(String id, Label label, boolean visible, boolean clickable, int parent, Box box) {}
    record Result(boolean adPresent, LiveRules.Match match) {}

    static boolean inOpening(String pkg, String activity, long version, long started, long now) {
        return ownsWindow(pkg, activity) && version == SUPPORTED_VERSION
                && started >= 0 && now >= started && now - started <= OPENING_MS;
    }

    static boolean ownsWindow(String pkg, String activity) {
        return PACKAGE.equals(pkg) && MAIN.equals(activity);
    }

    static Label label(CharSequence value) {
        if (value == null || value.length() > 16) return Label.NONE;
        String text = value.toString().strip();
        if ("品牌广告".equals(text)) return Label.BRAND_AD;
        return SKIP.matcher(text).matches() ? Label.SKIP : Label.NONE;
    }

    static Result match(List<Node> nodes, Box viewport) {
        int badge = -1;
        int skip = -1;
        boolean denied = false;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (BLOCKED_VIEW.equals(node.id())) denied = true;
            if (!node.visible()) continue;
            if (node.label() == Label.BRAND_AD) badge = badge == -1 ? i : -2;
            if (node.label() == Label.SKIP) skip = skip == -1 ? i : -2;
        }
        boolean present = badge != -1;
        if (denied || badge < 0 || skip < 0 || viewport.width() <= 0 || viewport.height() <= 0) {
            return new Result(present, null);
        }
        int container = adContainer(nodes, badge);
        if (container < 0 || container != adContainer(nodes, skip)
                || !viewport.equals(nodes.get(container).box())) return new Result(true, null);
        Box badgeBox = nodes.get(badge).box();
        Box skipBox = nodes.get(skip).box();
        if (!viewport.contains(badgeBox) || !inTopRight(skipBox, viewport)
                || badgeBox.right() > skipBox.left()
                || badgeBox.bottom() <= skipBox.top() || skipBox.bottom() <= badgeBox.top()) {
            return new Result(true, null);
        }
        int target = skip;
        for (int depth = 0; depth < 3 && target >= 0 && target < nodes.size(); depth++) {
            Node node = nodes.get(target);
            if (node.clickable()) {
                // Bounds validate the semantics of the node; they are never used to inject a tap.
                if (!node.visible() || !node.box().contains(skipBox) || !inTopRight(node.box(), viewport)
                        || node.box().width() > viewport.width() * .28
                        || node.box().height() > viewport.height() * .12
                        || node.box().left() < badgeBox.right()) return new Result(true, null);
                return new Result(true, new LiveRules.Match(RULE, target));
            }
            target = node.parent();
        }
        return new Result(true, null);
    }

    private static boolean inTopRight(Box box, Box viewport) {
        return viewport.contains(box) && box.left() >= viewport.left() + viewport.width() * .60
                && box.bottom() <= viewport.top() + viewport.height() * .23;
    }

    private static int adContainer(List<Node> nodes, int index) {
        for (int depth = 0; depth < 5 && index >= 0 && index < nodes.size(); depth++) {
            Node node = nodes.get(index);
            if (node.visible() && AD_CONTAINER.equals(node.id())) return index;
            index = node.parent();
        }
        return -1;
    }
}
