package club.heiqi.qz_miner.toolswap;

/**
 * 自动工具优先级选择器的不可变值对象。
 */
public final class ToolSelector {

    /** 选择器种类。 */
    public enum Kind {
        ITEM,
        ORE
    }

    private final Kind kind;
    private final String name;
    private final Integer subtype;

    private ToolSelector(Kind kind, String name, Integer subtype) {
        this.kind = kind;
        this.name = name;
        this.subtype = subtype;
    }

    /** @return 通配 subtype 的物品选择器 */
    public static ToolSelector itemWildcard(String registryId) {
        return new ToolSelector(Kind.ITEM, registryId, null);
    }

    /** @return 精确 subtype 的物品选择器 */
    public static ToolSelector itemSubtype(String registryId, int subtype) {
        if (subtype < 0) {
            throw new IllegalArgumentException("subtype must be non-negative");
        }
        return new ToolSelector(Kind.ITEM, registryId, Integer.valueOf(subtype));
    }

    /** @return 矿辞选择器 */
    public static ToolSelector ore(String oreName) {
        return new ToolSelector(Kind.ORE, oreName, null);
    }

    public Kind kind() {
        return kind;
    }

    /** @return registry id 或矿辞名 */
    public String name() {
        return name;
    }

    /** @return 精确 subtype；通配或矿辞选择器返回 null */
    public Integer subtype() {
        return subtype;
    }

    /** @return 稳定规范文本 */
    public String canonicalText() {
        if (kind == Kind.ORE) {
            return "ore:" + name;
        }
        return name + "@" + (subtype == null ? "*" : subtype.toString());
    }

    /**
     * 判断候选是否命中。该操作只读已捕获身份，不访问 registry 或 OreDictionary。
     *
     * @param candidate 候选
     * @return 是否命中
     */
    public boolean matches(ToolCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (kind == Kind.ORE) {
            return candidate.oreNames().contains(name);
        }
        return name.equals(candidate.registryId())
                && (subtype == null || subtype.intValue() == candidate.subtype());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ToolSelector)) {
            return false;
        }
        ToolSelector that = (ToolSelector) other;
        return kind == that.kind && name.equals(that.name)
                && (subtype == null ? that.subtype == null : subtype.equals(that.subtype));
    }

    @Override
    public int hashCode() {
        int result = 31 * kind.hashCode() + name.hashCode();
        return 31 * result + (subtype == null ? 0 : subtype.hashCode());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
