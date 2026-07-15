package club.heiqi.qz_miner.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无 registry 副作用的严格工具选择器解析器。
 */
public final class ToolSelectorParser {

    private static final Pattern ITEM = Pattern.compile(
            "^([a-z0-9_.-]+:[a-z0-9_./-]+)@([*]|[0-9]+)$");

    private ToolSelectorParser() {
    }

    /**
     * 解析有序选择器列表，并按输入索引报告错误。
     *
     * @param raw 原始列表
     * @param path 配置路径
     * @return 解析结果
     */
    public static ParseResult parseList(Object raw, String path) {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        List<ToolSelector> selectors = new ArrayList<ToolSelector>();
        if (!(raw instanceof List<?>)) {
            errors.put(path, path + " must be LIST, got " + typeName(raw));
            return new ParseResult(selectors, errors);
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<?> values = (List<?>) raw;
        for (int index = 0; index < values.size(); index++) {
            String indexedPath = path + "[" + index + "]";
            Object value = values.get(index);
            if (!(value instanceof String)) {
                errors.put(indexedPath, indexedPath + " must be STRING, got " + typeName(value));
                continue;
            }
            try {
                ToolSelector selector = parse((String) value);
                if (!seen.add(selector.canonicalText())) {
                    errors.put(indexedPath, indexedPath + " duplicates selector " + selector.canonicalText());
                } else {
                    selectors.add(selector);
                }
            } catch (IllegalArgumentException e) {
                errors.put(indexedPath, indexedPath + " " + e.getMessage());
            }
        }
        return new ParseResult(selectors, errors);
    }

    /**
     * 解析单个选择器。合法但当前未知的名字只作为文本保留。
     *
     * @param raw 原始文本
     * @return 规范化选择器
     */
    public static ToolSelector parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("must not be null");
        }
        String value = raw.trim();
        if (value.startsWith("ore:")) {
            String oreName = value.substring(4).trim();
            if (oreName.length() == 0) {
                throw new IllegalArgumentException("must use ore:<non-empty-name>");
            }
            return ToolSelector.ore(oreName);
        }
        Matcher matcher = ITEM.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "must use <namespace:path>@* or <namespace:path>@<non-negative-integer>");
        }
        String registryId = matcher.group(1);
        String subtypeText = matcher.group(2);
        if ("*".equals(subtypeText)) {
            return ToolSelector.itemWildcard(registryId);
        }
        try {
            return ToolSelector.itemSubtype(registryId, Integer.parseInt(subtypeText));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("has subtype outside integer range", e);
        }
    }

    private static String typeName(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }

    /** 解析结果。 */
    public static final class ParseResult {
        private final List<ToolSelector> selectors;
        private final Map<String, String> errors;

        private ParseResult(List<ToolSelector> selectors, Map<String, String> errors) {
            this.selectors = Collections.unmodifiableList(new ArrayList<ToolSelector>(selectors));
            this.errors = Collections.unmodifiableMap(new LinkedHashMap<String, String>(errors));
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<ToolSelector> selectors() {
            return selectors;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }
}
