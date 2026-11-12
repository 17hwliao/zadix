package com.ahuamiao.adprotection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONTokener;

/** Matches the built-in test rules against a flat snapshot. This is an Ad-Killer format. */
final class LiveRules {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "id", "packageName", "minVersionCode", "maxVersionCode", "activityName",
            "adMarkerId", "closeViewId", "blockedViewIds", "action");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern RULE_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    record Node(String viewId, boolean visible, boolean clickable) {}
    record Match(String ruleId, int targetIndex) {}

    private record Spec(String id, String packageName, long minVersion, long maxVersion,
                        String activity, String marker, String close, Set<String> blocked) {
        boolean supports(String name, long version) {
            return packageName.equals(name) && version >= minVersion && version <= maxVersion;
        }

        boolean applies(String name, long version, String activityName) {
            return supports(name, version) && activity.equals(activityName);
        }
    }

    private final List<Spec> rules;
    private final Set<String> packages;

    private LiveRules(List<Spec> rules, Set<String> packages) {
        this.rules = List.copyOf(rules);
        this.packages = Set.copyOf(packages);
    }

    static LiveRules parse(String json) throws Exception {
        if (json == null || json.length() > 32_768) throw invalid("Invalid rule file size");
        JsonReader reader = new JsonReader(json);
        Map<?, ?> root = fields(reader.value(0), ROOT_FIELDS);
        if (reader.peek() != -1 || integer(root, "schemaVersion") != 1) {
            throw invalid("Unsupported rule file");
        }
        if (!(root.get("rules") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 32) {
            throw invalid("Expected 1 to 32 live rules");
        }
        List<Spec> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> packages = new HashSet<>();
        for (Object entry : entries) {
            Spec rule = parseRule(fields(entry, RULE_FIELDS));
            if (!ids.add(rule.id())) throw invalid("Duplicate rule id");
            packages.add(rule.packageName());
            rules.add(rule);
        }
        return new LiveRules(rules, packages);
    }

    private static Spec parseRule(Map<?, ?> rule) {
        String id = string(rule, "id");
        String name = string(rule, "packageName");
        String activity = string(rule, "activityName");
        long min = integer(rule, "minVersionCode");
        long max = integer(rule, "maxVersionCode");
        if (!RULE_ID.matcher(id).matches() || !PACKAGE_NAME.matcher(name).matches()
                || !CLASS_NAME.matcher(activity).matches() || min < 1 || max < min
                || !"clickNode".equals(rule.get("action"))) {
            throw invalid("Invalid live rule identity, version range or action");
        }
        String marker = viewId(name, rule.get("adMarkerId"));
        String close = viewId(name, rule.get("closeViewId"));
        if (marker.equals(close)) throw invalid("Ad marker and close control must differ");
        if (!(rule.get("blockedViewIds") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 16) {
            throw invalid("Expected 1 to 16 blocked view IDs");
        }
        Set<String> blocked = new HashSet<>();
        for (Object entry : entries) {
            String view = viewId(name, entry);
            if (view.equals(marker) || view.equals(close) || !blocked.add(view)) {
                throw invalid("Blocked view conflicts with another view in this rule");
            }
        }
        return new Spec(id, name, min, max, activity, marker, close, Set.copyOf(blocked));
    }

    Set<String> packages() {
        return packages;
    }

    boolean supports(String packageName, long versionCode) {
        for (Spec rule : rules) if (rule.supports(packageName, versionCode)) return true;
        return false;
    }

    boolean hasAdMarker(String packageName, long versionCode, String activityName, List<Node> nodes) {
        for (Spec rule : rules) {
            if (!rule.applies(packageName, versionCode, activityName)) continue;
            for (Node node : nodes) {
                if (node != null && rule.marker().equals(node.viewId())) return true;
            }
        }
        return false;
    }

    Match match(String packageName, long versionCode, String activityName, List<Node> nodes) {
        Match result = null;
        for (Spec rule : rules) {
            if (!rule.applies(packageName, versionCode, activityName)) continue;
            int markerCount = 0;
            int closeCount = 0;
            boolean markerVisible = false;
            int target = -1;
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (node == null || node.viewId() == null) continue;
                if (rule.blocked().contains(node.viewId())) return null;
                if (rule.marker().equals(node.viewId())) {
                    markerCount++;
                    markerVisible |= node.visible();
                }
                if (rule.close().equals(node.viewId())) {
                    closeCount++;
                    if (node.visible() && node.clickable()) target = i;
                }
            }
            if (markerCount == 1 && markerVisible && closeCount == 1 && target != -1) {
                if (result != null) return null;
                result = new Match(rule.id(), target);
            }
        }
        return result;
    }

    private static Map<?, ?> fields(Object value, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> object) || !allowed.equals(object.keySet())) {
            throw invalid("Missing or unsupported rule fields");
        }
        return object;
    }

    private static long integer(Map<?, ?> object, String key) {
        if (object.get(key) instanceof Long number) return number;
        throw invalid("Expected an integer in " + key);
    }

    private static String string(Map<?, ?> object, String key) {
        if (object.get(key) instanceof String text && !text.isEmpty() && text.length() <= 256) return text;
        throw invalid("Expected a bounded string in " + key);
    }

    private static String viewId(String packageName, Object value) {
        String prefix = packageName + ":id/";
        if (value instanceof String text && text.length() <= 384 && text.startsWith(prefix)
                && RESOURCE_ID.matcher(text.substring(prefix.length())).matches()) {
            return text;
        }
        throw invalid("Expected a fully qualified view ID owned by the target package");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    // Android JSONObject accepts duplicate keys. Keep object parsing explicit so an
    // invalid rule cannot overwrite an earlier safety constraint during decoding.
    private static final class JsonReader {
        private final String input;
        private int offset;

        JsonReader(String input) {
            this.input = input;
        }

        Object value(int depth) throws Exception {
            if (depth > 4) throw invalid("Live rule file is nested too deeply");
            return switch (peek()) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) throws Exception {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            do {
                String key = string();
                if (result.containsKey(key)) throw invalid("Duplicate rule field");
                expect(':');
                result.put(key, value(depth + 1));
            } while (take(','));
            expect('}');
            return result;
        }

        private List<Object> array(int depth) throws Exception {
            expect('[');
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            do {
                result.add(value(depth + 1));
            } while (take(','));
            expect(']');
            return result;
        }

        private String string() throws Exception {
            expect('"');
            int start = offset - 1;
            while (offset < input.length()) {
                char ch = input.charAt(offset++);
                if (ch == '"') {
                    return (String) new JSONTokener(input.substring(start, offset)).nextValue();
                }
                if (ch < 0x20) break;
                if (ch == '\\') {
                    if (offset == input.length()) break;
                    char escape = input.charAt(offset++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (offset == input.length()
                                    || "0123456789abcdefABCDEF".indexOf(input.charAt(offset++)) == -1) {
                                throw invalid("Invalid JSON Unicode escape");
                            }
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) == -1) {
                        break;
                    }
                }
            }
            throw invalid("Invalid JSON string");
        }

        private long number() {
            int start = offset;
            int first = peek();
            if (first < '0' || first > '9') throw invalid("Expected an unsigned integer");
            offset++;
            if (first != '0') {
                while (offset < input.length() && input.charAt(offset) >= '0' && input.charAt(offset) <= '9') {
                    offset++;
                }
            }
            return Long.parseLong(input.substring(start, offset));
        }

        private void expect(char ch) {
            if (!take(ch)) throw invalid("Invalid JSON structure");
        }

        private boolean take(char ch) {
            if (peek() != ch) return false;
            offset++;
            return true;
        }

        int peek() {
            while (offset < input.length()) {
                char ch = input.charAt(offset);
                if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') return ch;
                offset++;
            }
            return -1;
        }
    }
}
