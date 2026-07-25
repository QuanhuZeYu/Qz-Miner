package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变的 registry + metadata 选择器。
 */
public final class ObjectGroupSelector {

    public static final int MAX_REGISTRY_LENGTH = 256;
    /** canonical selector 仅含 ASCII，字符数同时就是 UTF-8 wire 字节数。 */
    public static final int MAX_CANONICAL_LENGTH = 1024;

    /** 选择器具体性，数值越小优先级越高。 */
    public enum Specificity {
        SINGLE,
        SET,
        WILDCARD
    }

    private final String registry;
    private final Specificity specificity;
    private final List<Integer> metadata;
    private final String canonical;

    private ObjectGroupSelector(String registry, Specificity specificity, List<Integer> metadata) {
        this.registry = registry;
        this.specificity = specificity;
        this.metadata = Collections.unmodifiableList(new ArrayList<Integer>(metadata));
        String value;
        if (specificity == Specificity.SINGLE) {
            value = registry + "@" + metadata.get(0);
        } else if (specificity == Specificity.WILDCARD) {
            value = registry + "@*";
        } else {
            StringBuilder out = new StringBuilder(registry).append("@[");
            for (int i = 0; i < metadata.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(metadata.get(i));
                if (out.length() >= MAX_CANONICAL_LENGTH) {
                    throw new IllegalArgumentException("selector exceeds 1024-byte wire limit");
                }
            }
            value = out.append(']').toString();
        }
        if (value.length() > MAX_CANONICAL_LENGTH) {
            throw new IllegalArgumentException("selector exceeds 1024-byte wire limit");
        }
        canonical = value;
    }

    /** 创建单 metadata 选择器。 */
    public static ObjectGroupSelector single(String registry, int meta) {
        checkRegistry(registry);
        checkMeta(meta);
        return new ObjectGroupSelector(registry, Specificity.SINGLE,
                Collections.singletonList(Integer.valueOf(meta)));
    }

    /** 创建 metadata 集合选择器。 */
    public static ObjectGroupSelector set(String registry, List<Integer> metas) {
        checkRegistry(registry);
        if (metas == null || metas.isEmpty()) {
            throw new IllegalArgumentException("metadata set must not be empty");
        }
        int largestPossibleMemberCount = (MAX_CANONICAL_LENGTH - registry.length() - 2) / 2;
        if (metas.size() > largestPossibleMemberCount) {
            throw new IllegalArgumentException("selector exceeds 1024-byte wire limit");
        }
        List<Integer> copy = new ArrayList<Integer>();
        for (Integer meta : metas) {
            if (meta == null) {
                throw new IllegalArgumentException("metadata must not be null");
            }
            checkMeta(meta.intValue());
            if (copy.contains(meta)) {
                throw new IllegalArgumentException("metadata set must be unique");
            }
            copy.add(meta);
        }
        Collections.sort(copy);
        return new ObjectGroupSelector(registry, Specificity.SET, copy);
    }

    /** 创建通配 metadata 选择器。 */
    public static ObjectGroupSelector wildcard(String registry) {
        checkRegistry(registry);
        return new ObjectGroupSelector(registry, Specificity.WILDCARD,
                Collections.<Integer>emptyList());
    }

    public String registry() {
        return registry;
    }

    public Specificity specificity() {
        return specificity;
    }

    public List<Integer> metadata() {
        return metadata;
    }

    public boolean matches(String candidateRegistry, int candidateMeta) {
        if (!registry.equals(candidateRegistry) || candidateMeta < 0) {
            return false;
        }
        return specificity == Specificity.WILDCARD
                || Collections.binarySearch(metadata, Integer.valueOf(candidateMeta)) >= 0;
    }

    /** @return 标准化后的完整 selector 语法 */
    public String canonical() {
        return canonical;
    }

    @Override
    public String toString() {
        return canonical;
    }

    private static void checkRegistry(String registry) {
        if (registry == null || registry.isEmpty() || registry.length() > MAX_REGISTRY_LENGTH
                || registry.indexOf('@') >= 0 || registry.indexOf(' ') >= 0
                || registry.indexOf('\t') >= 0 || registry.indexOf('\r') >= 0
                || registry.indexOf('\n') >= 0 || !registry.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9/._-]+")) {
            throw new IllegalArgumentException("invalid registry: " + registry);
        }
    }

    private static void checkMeta(int meta) {
        if (meta < 0) {
            throw new IllegalArgumentException("metadata must be a non-negative int: " + meta);
        }
    }
}
