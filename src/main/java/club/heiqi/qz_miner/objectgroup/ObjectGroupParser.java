package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

import club.heiqi.qz_miner.network.ObjectGroupWireConfig;

/**
 * 解析并校验对象组配置和完整 registry@meta selector 语法。
 */
public final class ObjectGroupParser {

    private ObjectGroupParser() {
    }

    /** 从结构化配置值解析不可变规则集。 */
    public static ParseResult parse(Object raw) {
        if (!(raw instanceof List)) {
            return ParseResult.invalid("objectGroups must be a list");
        }
        List<?> rawGroups = (List<?>) raw;
        if (rawGroups.size() > ObjectGroupRuleSet.MAX_GROUPS) {
            return ParseResult.invalid("objectGroups exceeds 64 groups");
        }
        List<ObjectGroup> groups = new ArrayList<ObjectGroup>();
        Set<String> ids = new HashSet<String>();
        int totalMembers = 0;
        for (int i = 0; i < rawGroups.size(); i++) {
            Object rawGroup = rawGroups.get(i);
            String path = "client.objectGroups[" + i + "]";
            if (!(rawGroup instanceof Map)) {
                return ParseResult.invalid(path + " must be an object");
            }
            Map<?, ?> map = (Map<?, ?>) rawGroup;
            Object rawId = map.get("id");
            if (!(rawId instanceof String)) {
                return ParseResult.invalid(path + ".id must be a string");
            }
            String id = (String) rawId;
            if (id.isEmpty() || id.length() > ObjectGroup.MAX_ID_LENGTH) {
                return ParseResult.invalid(path + ".id is empty or too long");
            }
            if (!ids.add(id)) {
                return ParseResult.invalid(path + ".id is duplicated");
            }
            Object rawModes = map.containsKey("modes") ? map.get("modes") : java.util.Collections.emptyList();
            if (!(rawModes instanceof List)) {
                return ParseResult.invalid(path + ".modes must be a list");
            }
            List<String> modes = new ArrayList<String>();
            long modeMask;
            try {
                for (Object mode : (List<?>) rawModes) {
                    if (!(mode instanceof String)) {
                        return ParseResult.invalid(path + ".modes must contain choices");
                    }
                    modes.add((String) mode);
                }
                modeMask = ObjectGroupMode.toMask(modes);
            } catch (IllegalArgumentException e) {
                return ParseResult.invalid(path + ".modes: " + e.getMessage());
            }
            Object rawMembers = map.get("members");
            if (!(rawMembers instanceof List)) {
                return ParseResult.invalid(path + ".members must be a list");
            }
            List<?> memberValues = (List<?>) rawMembers;
            if (memberValues.isEmpty() || memberValues.size() > ObjectGroup.MAX_MEMBERS) {
                return ParseResult.invalid(path + ".members must contain 1..128 selectors");
            }
            totalMembers += memberValues.size();
            if (totalMembers > ObjectGroupRuleSet.MAX_TOTAL_MEMBERS) {
                return ParseResult.invalid("client.objectGroups total members exceeds 2048");
            }
            LinkedHashMap<String, Integer> selectorMasks = new LinkedHashMap<String, Integer>();
            for (int j = 0; j < memberValues.size(); j++) {
                Object value = memberValues.get(j);
                if (!(value instanceof String)) {
                    return ParseResult.invalid(path + ".members[" + j + "] must be a string");
                }
                try {
                    ObjectGroupSelector selector = parseSelector((String) value);
                    Integer previous = selectorMasks.get(selector.registry());
                    selectorMasks.put(selector.registry(), Integer.valueOf(
                            (previous == null ? 0 : previous.intValue()) | selector.metadataMask()));
                } catch (IllegalArgumentException e) {
                    return ParseResult.invalid(path + ".members[" + j + "]: " + e.getMessage());
                }
            }
            List<ObjectGroupSelector> selectors = new ArrayList<ObjectGroupSelector>();
            for (Map.Entry<String, Integer> selector : selectorMasks.entrySet()) {
                selectors.add(ObjectGroupSelector.fromMask(selector.getKey(), selector.getValue().intValue()));
            }
            try {
                groups.add(new ObjectGroup(id, modes, modeMask, selectors));
            } catch (IllegalArgumentException e) {
                return ParseResult.invalid(path + ": " + e.getMessage());
            }
        }
        try {
            Map<String, String> overlapErrors = findOverlapErrors(groups);
            return overlapErrors.isEmpty()
                    ? ParseResult.valid(new ObjectGroupRuleSet(groups)) : ParseResult.invalid(overlapErrors);
        } catch (IllegalArgumentException e) {
            return ParseResult.invalid("client.objectGroups: " + e.getMessage());
        }
    }

    /** 从网络层已限幅的原始组解析规则集。 */
    public static ParseResult parse(ObjectGroupWireConfig wire) {
        if (wire == null || !wire.isValid()) {
            return ParseResult.invalid("invalid object group payload");
        }
        List<Object> groups = new ArrayList<Object>();
        for (ObjectGroupWireConfig.RawGroup rawGroup : wire.groups()) {
            java.util.LinkedHashMap<String, Object> group = new java.util.LinkedHashMap<String, Object>();
            group.put("id", rawGroup.id());
            group.put("modes", modesForMask(rawGroup.modeMask()));
            group.put("members", new ArrayList<String>(rawGroup.members()));
            groups.add(group);
        }
        return parse(groups);
    }

    private static List<String> modesForMask(long mask) {
        List<String> modes = new ArrayList<String>();
        for (String id : ObjectGroupMode.ids()) {
            if ((mask & ObjectGroupMode.toMask(java.util.Collections.singletonList(id))) != 0L) {
                modes.add(id);
            }
        }
        return modes;
    }

    private static Map<String, String> findOverlapErrors(List<ObjectGroup> groups) {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        for (int left = 0; left < groups.size(); left++) {
            for (int right = left + 1; right < groups.size(); right++) {
                ObjectGroup a = groups.get(left);
                ObjectGroup b = groups.get(right);
                if ((a.modeMask() & b.modeMask()) == 0L || !selectorsOverlap(a, b)) {
                    continue;
                }
                String message = "object groups share a mode and overlapping selector: " + a.id() + " / " + b.id();
                errors.put("client.objectGroups[" + left + "].modes", message);
                errors.put("client.objectGroups[" + right + "].modes", message);
            }
        }
        return errors;
    }

    private static boolean selectorsOverlap(ObjectGroup left, ObjectGroup right) {
        for (ObjectGroupSelector a : left.members()) {
            for (ObjectGroupSelector b : right.members()) {
                if (a.registry().equals(b.registry()) && (a.metadataMask() & b.metadataMask()) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 解析 registry@0、registry@*、registry@[0,4,8,12]。 */
    public static ObjectGroupSelector parseSelector(String value) {
        if (value == null || value.isEmpty() || value.length() > ObjectGroupSelector.MAX_REGISTRY_LENGTH + 20) {
            throw new IllegalArgumentException("selector is empty or too long");
        }
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1 || at != value.indexOf('@')) {
            throw new IllegalArgumentException("selector must be registry@meta");
        }
        String registry = value.substring(0, at);
        String meta = value.substring(at + 1);
        if (meta.equals("*")) {
            return ObjectGroupSelector.wildcard(registry);
        }
        if (meta.length() >= 3 && meta.charAt(0) == '[' && meta.charAt(meta.length() - 1) == ']') {
            String body = meta.substring(1, meta.length() - 1);
            if (body.isEmpty()) {
                throw new IllegalArgumentException("metadata set must not be empty");
            }
            String[] parts = body.split(",", -1);
            List<Integer> values = new ArrayList<Integer>();
            for (String part : parts) {
                values.add(Integer.valueOf(parseMeta(part)));
            }
            return ObjectGroupSelector.set(registry, values);
        }
        return ObjectGroupSelector.single(registry, parseMeta(meta));
    }

    private static int parseMeta(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("metadata is empty");
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw new IllegalArgumentException("metadata must be an integer in [0,15]");
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 15) {
                throw new IllegalArgumentException("metadata must be in [0,15]");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("metadata is too large", e);
        }
    }

    /** 解析结果，错误时不提供部分规则集。 */
    public static final class ParseResult {
        private final ObjectGroupRuleSet rules;
        private final String error;
        private final Map<String, String> errors;

        private ParseResult(ObjectGroupRuleSet rules, Map<String, String> errors) {
            this.rules = rules;
            this.errors = java.util.Collections.unmodifiableMap(new LinkedHashMap<String, String>(errors));
            this.error = errors.isEmpty() ? null : errors.values().iterator().next();
        }

        static ParseResult valid(ObjectGroupRuleSet rules) {
            return new ParseResult(rules, java.util.Collections.<String, String>emptyMap());
        }

        static ParseResult invalid(String error) {
            Map<String, String> errors = new LinkedHashMap<String, String>();
            errors.put("client.objectGroups", error);
            return new ParseResult(null, errors);
        }

        static ParseResult invalid(Map<String, String> errors) {
            return new ParseResult(null, errors);
        }

        public boolean isValid() {
            return rules != null && error == null;
        }

        public ObjectGroupRuleSet rules() {
            return rules;
        }

        public String error() {
            return error;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }
}
