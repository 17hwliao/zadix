package com.ahuamiao.adprotection;

import java.util.List;
import java.util.Set;

/** Matches the observed opening ad capsule, not the mini-program close or ad feedback controls. */
final class QqMiniAppSplash {
    static final String PACKAGE = "com.tencent.mobileqq";
    static final String ACTIVITY = "com.tencent.luggage.wxa.standalone_open_runtime.container.WxaContainerMultiTaskActivity0";
    static final String RULE = "qq-miniapp-startup";
    static final long READY_DELAY_MS = 850;
    static final long EARLY_TEST_DELAY_MS = 400;
    private static final int VERSION = 16750;
    private static final String FRAME = "android.widget.FrameLayout";
    private static final String TEXT = "android.widget.TextView";
    private static final String IMAGE_BUTTON = "android.widget.ImageButton";
    private static final Set<String> BLOCKED = Set.of("登录", "登录授权", "确认支付", "请输入密码", "输入验证码", "权限申请");

    enum Label { NONE, AD, SKIP, BLOCKED }
    record Node(String id, String kind, Label label, boolean visible, boolean enabled,
                int parent, NetdiskSplash.Box box) {}

    static boolean ownsWindow(String pkg, String activity) {
        return PACKAGE.equals(pkg) && ACTIVITY.equals(activity);
    }

    static boolean inOpening(String pkg, String activity, long version, long started, long now) {
        return ownsWindow(pkg, activity) && supports(pkg, version) && started >= 0
                && now >= started && now - started <= 10_000;
    }

    static boolean supports(String pkg, long version) {
        return PACKAGE.equals(pkg) && version == VERSION;
    }

    static boolean readyForScheduledCheck(long firstMatch, long now) {
        return firstMatch >= 0 && now >= firstMatch && now - firstMatch >= READY_DELAY_MS;
    }

    static Label label(CharSequence text, CharSequence description) {
        String value = text == null ? "" : text.toString().strip();
        String desc = description == null ? "" : description.toString().strip();
        if (BLOCKED.contains(value) || BLOCKED.contains(desc)) return Label.BLOCKED;
        if ("广告".equals(value) || "广告".equals(desc)) return Label.AD;
        if ("跳过".equals(value) || "跳过".equals(desc)) return Label.SKIP;
        return Label.NONE;
    }

    static LiveRules.Match match(List<Node> nodes, NetdiskSplash.Box viewport) {
        int ad = -1;
        int skip = -1;
        NetdiskSplash.Box menu = null;
        NetdiskSplash.Box close = null;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (!node.visible()) continue;
            if (node.label() == Label.BLOCKED || "android.widget.EditText".equals(node.kind())) return null;
            if (!node.enabled()) continue;
            if (TEXT.equals(node.kind()) && noId(node.id())) {
                if (node.label() == Label.AD) {
                    if (ad != -1) return null;
                    ad = i;
                }
                if (node.label() == Label.SKIP) {
                    if (skip != -1) return null;
                    skip = i;
                }
            }
            if (IMAGE_BUTTON.equals(node.kind())) {
                if ((PACKAGE + ":id/57").equals(node.id())) {
                    if (menu != null && !menu.equals(node.box())) return null;
                    menu = node.box();
                }
                if ((PACKAGE + ":id/56").equals(node.id())) {
                    if (close != null && !close.equals(node.box())) return null;
                    close = node.box();
                }
            }
        }
        if (ad < 0 || skip < 0 || menu == null || close == null) return null;
        NetdiskSplash.Box badge = nodes.get(ad).box();
        NetdiskSplash.Box target = nodes.get(skip).box();
        if (!viewport.contains(badge) || !viewport.contains(target)
                || !viewport.contains(menu) || !viewport.contains(close)
                || target.width() > viewport.width() * .20
                || badge.right() >= target.left() || !overlapsVertically(badge, target)
                || menu.left() < viewport.left() + viewport.width() * .62 || menu.right() > close.left()
                || !overlapsVertically(target, menu) || !overlapsVertically(target, close)) return null;
        int pill = nodes.get(skip).parent();
        for (int depth = 0; depth < 5 && pill >= 0 && pill < nodes.size(); depth++) {
            Node container = nodes.get(pill);
            var box = container.box();
            if (FRAME.equals(container.kind()) && container.visible() && noId(container.id())
                    && viewport.contains(box) && box.contains(badge) && box.contains(target)
                    && box.right() <= viewport.left() + viewport.width() * .44
                    && box.bottom() <= viewport.top() + viewport.height() * .20
                    && box.height() <= viewport.height() * .10
                    && descendantOf(nodes, ad, pill) && hasDivider(nodes, pill, badge, target, box)) {
                return new LiveRules.Match(RULE, skip);
            }
            pill = container.parent();
        }
        return null;
    }

    private static boolean hasDivider(List<Node> nodes, int pill, NetdiskSplash.Box ad,
                                      NetdiskSplash.Box skip, NetdiskSplash.Box container) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            var box = node.box();
            if (node.visible() && FRAME.equals(node.kind()) && container.contains(box)
                    && box.left() >= ad.right() && box.right() <= skip.left()
                    && box.width() <= Math.max(2, container.width() * .025)
                    && box.height() >= skip.height() * .45 && overlapsVertically(box, skip)
                    && descendantOf(nodes, i, pill)) return true;
        }
        return false;
    }

    private static boolean descendantOf(List<Node> nodes, int child, int parent) {
        for (int i = 0; i < 8 && child >= 0 && child < nodes.size(); i++) {
            child = nodes.get(child).parent();
            if (child == parent) return true;
        }
        return false;
    }

    private static boolean overlapsVertically(NetdiskSplash.Box a, NetdiskSplash.Box b) {
        return a.top() < b.bottom() && b.top() < a.bottom();
    }

    private static boolean noId(String id) { return id == null || id.isEmpty(); }
}
