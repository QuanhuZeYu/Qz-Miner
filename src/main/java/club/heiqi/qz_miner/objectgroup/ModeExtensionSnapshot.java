package club.heiqi.qz_miner.objectgroup;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 单次任务冻结的对象组扩展，热路径按 registry 查完整非负 int metadata 域。 */
public final class ModeExtensionSnapshot {
    public static final ModeExtensionSnapshot EMPTY = new ModeExtensionSnapshot(
            null, Collections.<String, ObjectGroupMetadataDomain>emptyMap());

    private final String groupId;
    private final Map<String, ObjectGroupMetadataDomain> metadataDomains;

    private ModeExtensionSnapshot(String groupId, Map<String, ObjectGroupMetadataDomain> metadataDomains) {
        this.groupId = groupId;
        this.metadataDomains = metadataDomains;
    }

    /** 从已选择对象组构建不可变索引。 */
    public static ModeExtensionSnapshot from(ObjectGroup group) {
        Map<String, ObjectGroupMetadataDomain> domains =
                new HashMap<String, ObjectGroupMetadataDomain>();
        for (ObjectGroupSelector selector : group.members()) {
            ObjectGroupMetadataDomain domain = ObjectGroupMetadataDomain.from(selector);
            ObjectGroupMetadataDomain previous = domains.get(selector.registry());
            domains.put(selector.registry(), previous == null ? domain : previous.union(domain));
        }
        return new ModeExtensionSnapshot(group.id(), Collections.unmodifiableMap(domains));
    }

    public boolean matches(String registry, int meta) {
        ObjectGroupMetadataDomain domain = registry == null ? null : metadataDomains.get(registry);
        return domain != null && domain.matches(meta);
    }

    public boolean isEmpty() { return groupId == null; }
    public String groupId() { return groupId; }
}
