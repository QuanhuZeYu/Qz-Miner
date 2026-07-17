package club.heiqi.qz_miner.objectgroup;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 单次任务冻结的对象组扩展，热路径按 registry 查 16-bit metadata mask。 */
public final class ModeExtensionSnapshot {
    public static final ModeExtensionSnapshot EMPTY = new ModeExtensionSnapshot(null, Collections.<String, Integer>emptyMap());

    private final String groupId;
    private final Map<String, Integer> metadataMasks;

    private ModeExtensionSnapshot(String groupId, Map<String, Integer> metadataMasks) {
        this.groupId = groupId;
        this.metadataMasks = metadataMasks;
    }

    /** 从已选择对象组构建不可变索引。 */
    public static ModeExtensionSnapshot from(ObjectGroup group) {
        Map<String, Integer> masks = new HashMap<String, Integer>();
        for (ObjectGroupSelector selector : group.members()) {
            int mask = masks.containsKey(selector.registry()) ? masks.get(selector.registry()).intValue() : 0;
            masks.put(selector.registry(), Integer.valueOf(mask | selector.metadataMask()));
        }
        return new ModeExtensionSnapshot(group.id(), Collections.unmodifiableMap(masks));
    }

    public boolean matches(String registry, int meta) {
        Integer mask = registry == null ? null : metadataMasks.get(registry);
        return mask != null && meta >= 0 && meta < 16 && (mask.intValue() & (1 << meta)) != 0;
    }

    public boolean isEmpty() { return groupId == null; }
    public String groupId() { return groupId; }
}
