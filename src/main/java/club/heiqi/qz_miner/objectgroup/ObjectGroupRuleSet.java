package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变、按配置顺序保存的每玩家对象组规则集。
 */
public final class ObjectGroupRuleSet {

    public static final int MAX_GROUPS = 64;
    public static final int MAX_TOTAL_MEMBERS = 2048;
    public static final ObjectGroupRuleSet EMPTY = new ObjectGroupRuleSet(Collections.<ObjectGroup>emptyList());

    private final List<ObjectGroup> groups;
    private final int totalMembers;

    public ObjectGroupRuleSet(List<ObjectGroup> groups) {
        if (groups == null || groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("object group count exceeds 64");
        }
        List<ObjectGroup> copy = new ArrayList<ObjectGroup>(groups);
        int members = 0;
        for (int i = 0; i < copy.size(); i++) {
            ObjectGroup group = copy.get(i);
            if (group == null) {
                throw new IllegalArgumentException("object group must not be null");
            }
            if (findById(copy, group.id(), i) >= 0) {
                throw new IllegalArgumentException("duplicate object group id: " + group.id());
            }
            members += group.members().size();
        }
        if (members > MAX_TOTAL_MEMBERS) {
            throw new IllegalArgumentException("object group total members exceeds 2048");
        }
        this.groups = Collections.unmodifiableList(copy);
        totalMembers = members;
    }

    public List<ObjectGroup> groups() {
        return groups;
    }

    public int totalMembers() {
        return totalMembers;
    }

    /** 按稳定模式位和种子选择唯一组并冻结其热路径索引。 */
    public ModeExtensionSnapshot resolve(long modeMask, String registry, int meta) {
        if (modeMask == 0L || registry == null) return ModeExtensionSnapshot.EMPTY;
        for (ObjectGroup group : groups) {
            if ((group.modeMask() & modeMask) != 0L && group.matches(registry, meta)) {
                return ModeExtensionSnapshot.from(group);
            }
        }
        return ModeExtensionSnapshot.EMPTY;
    }

    private static int findById(List<ObjectGroup> groups, String id, int before) {
        for (int i = 0; i < before; i++) {
            if (groups.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
