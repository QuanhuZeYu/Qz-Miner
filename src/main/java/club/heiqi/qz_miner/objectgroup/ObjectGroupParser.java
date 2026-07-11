package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
            List<ObjectGroupSelector> selectors = new ArrayList<ObjectGroupSelector>();
            for (int j = 0; j < memberValues.size(); j++) {
                Object value = memberValues.get(j);
                if (!(value instanceof String)) {
                    return ParseResult.invalid(path + ".members[" + j + "] must be a string");
                }
                try {
                    selectors.add(parseSelector((String) value));
                } catch (IllegalArgumentException e) {
                    return ParseResult.invalid(path + ".members[" + j + "]: " + e.getMessage());
                }
            }
            try {
                groups.add(new ObjectGroup(id, selectors));
            } catch (IllegalArgumentException e) {
                return ParseResult.invalid(path + ": " + e.getMessage());
            }
        }
        try {
            return ParseResult.valid(new ObjectGroupRuleSet(groups));
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
            group.put("members", new ArrayList<String>(rawGroup.members()));
            groups.add(group);
        }
        return parse(groups);
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

        private ParseResult(ObjectGroupRuleSet rules, String error) {
            this.rules = rules;
            this.error = error;
        }

        static ParseResult valid(ObjectGroupRuleSet rules) {
            return new ParseResult(rules, null);
        }

        static ParseResult invalid(String error) {
            return new ParseResult(null, error);
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
    }
}
