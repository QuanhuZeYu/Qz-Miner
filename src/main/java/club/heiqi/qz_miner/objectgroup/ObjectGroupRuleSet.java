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

    /**
     * 按单值、集合、通配的优先级选组；同级按组和成员的配置顺序取先者。
     */
    public ObjectGroup selectGroup(String registry, int meta) {
        ObjectGroup selected = null;
        ObjectGroupSelector.Specificity selectedSpecificity = null;
        for (ObjectGroup group : groups) {
            ObjectGroupSelector.Specificity specificity = group.specificityFor(registry, meta);
            if (specificity == null) {
                continue;
            }
            if (selected == null || specificity.ordinal() < selectedSpecificity.ordinal()) {
                selected = group;
                selectedSpecificity = specificity;
            }
        }
        return selected;
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
