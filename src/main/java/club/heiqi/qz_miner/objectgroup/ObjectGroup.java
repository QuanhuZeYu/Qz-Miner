package club.heiqi.qz_miner.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不可变对象组，成员顺序就是配置顺序。
 */
public final class ObjectGroup {

    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_MEMBERS = 128;

    private final String id;
    private final List<ObjectGroupSelector> members;

    public ObjectGroup(String id, List<ObjectGroupSelector> members) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("invalid object group id");
        }
        if (members == null || members.isEmpty() || members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("object group members must be in [1,128]");
        }
        this.id = id;
        List<ObjectGroupSelector> copy = new ArrayList<ObjectGroupSelector>(members);
        for (ObjectGroupSelector selector : copy) {
            if (selector == null) {
                throw new IllegalArgumentException("object group selector must not be null");
            }
        }
        this.members = Collections.unmodifiableList(copy);
    }

    public String id() {
        return id;
    }

    public List<ObjectGroupSelector> members() {
        return members;
    }

    /** 返回该组对一个 registry+meta 的匹配级别，未匹配返回 null。 */
    public ObjectGroupSelector.Specificity specificityFor(String registry, int meta) {
        ObjectGroupSelector.Specificity best = null;
        for (ObjectGroupSelector selector : members) {
            if (!selector.matches(registry, meta)) {
                continue;
            }
            if (best == null || selector.specificity().ordinal() < best.ordinal()) {
                best = selector.specificity();
            }
        }
        return best;
    }

    public boolean matches(String registry, int meta) {
        return specificityFor(registry, meta) != null;
    }
}
