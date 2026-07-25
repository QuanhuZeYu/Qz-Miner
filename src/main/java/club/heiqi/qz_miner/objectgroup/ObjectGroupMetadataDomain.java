package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变 metadata 域，表达完整非负 int 通配或由有界 selector 形成的有限集合。
 */
public final class ObjectGroupMetadataDomain {

    private static final ObjectGroupMetadataDomain WILDCARD =
            new ObjectGroupMetadataDomain(true, Collections.<Integer>emptyList());

    private final boolean wildcard;
    private final List<Integer> metadata;

    private ObjectGroupMetadataDomain(boolean wildcard, List<Integer> metadata) {
        this.wildcard = wildcard;
        this.metadata = Collections.unmodifiableList(new ArrayList<Integer>(metadata));
    }

    /** 从已校验且有界的 selector 建立 metadata 域。 */
    public static ObjectGroupMetadataDomain from(ObjectGroupSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        if (selector.specificity() == ObjectGroupSelector.Specificity.WILDCARD) {
            return WILDCARD;
        }
        return new ObjectGroupMetadataDomain(false, selector.metadata());
    }

    /** @return 与另一个域的不可变并集。 */
    public ObjectGroupMetadataDomain union(ObjectGroupMetadataDomain other) {
        if (other == null) {
            throw new IllegalArgumentException("metadata domain must not be null");
        }
        if (wildcard || other.wildcard) {
            return WILDCARD;
        }
        List<Integer> merged = new ArrayList<Integer>(metadata.size() + other.metadata.size());
        int left = 0;
        int right = 0;
        while (left < metadata.size() || right < other.metadata.size()) {
            if (left >= metadata.size()) {
                merged.add(other.metadata.get(right++));
            } else if (right >= other.metadata.size()) {
                merged.add(metadata.get(left++));
            } else {
                int leftValue = metadata.get(left).intValue();
                int rightValue = other.metadata.get(right).intValue();
                if (leftValue < rightValue) {
                    merged.add(metadata.get(left++));
                } else if (leftValue > rightValue) {
                    merged.add(other.metadata.get(right++));
                } else {
                    merged.add(metadata.get(left++));
                    right++;
                }
            }
        }
        return new ObjectGroupMetadataDomain(false, merged);
    }

    /** @return 非负 metadata 是否属于本域。 */
    public boolean matches(int candidateMetadata) {
        return candidateMetadata >= 0 && (wildcard
                || Collections.binarySearch(metadata, Integer.valueOf(candidateMetadata)) >= 0);
    }

    /** @return 两个非空域是否存在交集。 */
    public boolean intersects(ObjectGroupMetadataDomain other) {
        if (other == null) {
            return false;
        }
        if (wildcard || other.wildcard) {
            return true;
        }
        int left = 0;
        int right = 0;
        while (left < metadata.size() && right < other.metadata.size()) {
            int leftValue = metadata.get(left).intValue();
            int rightValue = other.metadata.get(right).intValue();
            if (leftValue == rightValue) {
                return true;
            }
            if (leftValue < rightValue) {
                left++;
            } else {
                right++;
            }
        }
        return false;
    }

    public boolean isWildcard() {
        return wildcard;
    }

    /** @return 有限域的排序去重快照；通配域返回空列表。 */
    public List<Integer> metadata() {
        return metadata;
    }
}
