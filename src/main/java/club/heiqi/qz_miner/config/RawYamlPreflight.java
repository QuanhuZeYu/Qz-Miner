package club.heiqi.qz_miner.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import club.heiqi.config.Config;
import club.heiqi.config.ConfigException;
import club.heiqi.config.ConfigFormat;
import club.heiqi.config.ConfigNode;
import club.heiqi.config.ConfigSource;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;

/**
 * 在 UILib Authority 宽松类型转换前检查原始 YAML 节点类型。
 *
 * <p>通过 MAP 的 {@code containsKey} 区分字段缺失与显式 null：缺失字段交给 schema 默认值，
 * 显式 null 拒绝。未知字段原样保留，不参与拒绝。</p>
 */
public final class RawYamlPreflight {

    private RawYamlPreflight() {
    }

    /**
     * 读取并检查 YAML 文件。
     *
     * @param file YAML 文件
     * @param schema 配置 schema
     * @return 原始类型检查结果
     * @throws ConfigException YAML 语法或读取失败
     */
    public static Result validate(File file, ConfigSchema schema) throws ConfigException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        ConfigNode root = file.isFile() && file.length() == 0L
                ? Config.parse("{}", ConfigFormat.YAML)
                : Config.load(ConfigSource.fromFile(file), ConfigFormat.YAML);
        return validate(root, schema);
    }

    /**
     * 检查已解析的配置树。
     *
     * @param root 原始根节点
     * @param schema 配置 schema
     * @return 原始类型检查结果
     */
    public static Result validate(ConfigNode root, ConfigSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        Map<String, String> errors = new LinkedHashMap<String, String>();
        if (root == null || root.getType() != ConfigNode.NodeType.MAP) {
            errors.put("_config", "YAML root must be MAP, got " + typeName(root));
            return new Result(errors);
        }
        for (FieldSpec field : schema.allFields()) {
            validateField(root, field, errors);
        }
        return new Result(errors);
    }

    private static void validateField(ConfigNode root, FieldSpec field, Map<String, String> errors) {
        String[] parts = field.path().split("\\.");
        ConfigNode current = root;
        StringBuilder traversed = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (current == null || current.getType() != ConfigNode.NodeType.MAP) {
                String path = traversed.length() == 0 ? "_config" : traversed.toString();
                putFirst(errors, path, path + " must be MAP, got " + typeName(current));
                return;
            }
            Map<String, ConfigNode> children = current.asMap();
            if (children == null || !children.containsKey(parts[i])) {
                return;
            }
            if (traversed.length() > 0) {
                traversed.append('.');
            }
            traversed.append(parts[i]);
            ConfigNode child = children.get(parts[i]);
            if (child == null || child.getType() == ConfigNode.NodeType.NULL) {
                putFirst(errors, traversed.toString(), traversed + " must not be null");
                return;
            }
            if (i < parts.length - 1) {
                if (child.getType() != ConfigNode.NodeType.MAP) {
                    putFirst(errors, traversed.toString(), traversed + " must be MAP, got " + child.getType());
                    return;
                }
                current = child;
                continue;
            }
            ConfigNode.NodeType expected = expectedNodeType(field.type());
            if (child.getType() != expected) {
                putFirst(errors, field.path(), field.path() + " must be " + expected + ", got " + child.getType());
            }
        }
    }

    private static ConfigNode.NodeType expectedNodeType(FieldType type) {
        switch (type) {
            case STRING:
            case CHOICE:
                return ConfigNode.NodeType.STRING;
            case NUMBER:
                return ConfigNode.NodeType.NUMBER;
            case BOOLEAN:
                return ConfigNode.NodeType.BOOLEAN;
            case SIMPLE_LIST:
                return ConfigNode.NodeType.LIST;
            default:
                throw new IllegalArgumentException("Unsupported field type: " + type);
        }
    }

    private static void putFirst(Map<String, String> errors, String path, String message) {
        if (!errors.containsKey(path)) {
            errors.put(path, message);
        }
    }

    private static String typeName(ConfigNode node) {
        return node == null ? "null" : node.getType().name();
    }

    /** 原始类型检查结果。 */
    public static final class Result {
        private final Map<String, String> errors;

        Result(Map<String, String> errors) {
            this.errors = Collections.unmodifiableMap(new LinkedHashMap<String, String>(errors));
        }

        /** @return 是否通过检查 */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /** @return path 到错误消息的不可变映射 */
        public Map<String, String> errors() {
            return errors;
        }

        /** @return 确定性错误摘要 */
        public String summary() {
            if (errors.isEmpty()) {
                return "ok";
            }
            StringBuilder out = new StringBuilder();
            for (String message : errors.values()) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(message);
            }
            return out.toString();
        }
    }
}
