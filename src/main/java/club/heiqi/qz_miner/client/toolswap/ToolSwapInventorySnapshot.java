package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.qz_miner.toolswap.ToolCandidate;

/** 纯核心所需的槽角色与工具候选快照。 */
public final class ToolSwapInventorySnapshot {

    private final boolean trusted;
    private final boolean fullCandidateScan;
    private final Map<Integer, SlotSnapshot> slots;
    private final List<ToolCandidate> candidates;

    public ToolSwapInventorySnapshot(List<SlotSnapshot> slots, List<ToolCandidate> candidates) {
        this(true, true, slots, candidates);
    }

    private ToolSwapInventorySnapshot(boolean trusted, boolean fullCandidateScan,
            List<SlotSnapshot> slots, List<ToolCandidate> candidates) {
        Map<Integer, SlotSnapshot> indexed = new LinkedHashMap<Integer, SlotSnapshot>();
        if (slots != null) {
            for (SlotSnapshot slot : slots) {
                if (slot == null || indexed.put(Integer.valueOf(slot.slot()), slot) != null) {
                    throw new IllegalArgumentException("slot snapshots must be non-null and unique");
                }
            }
        }
        this.trusted = trusted;
        this.fullCandidateScan = fullCandidateScan;
        this.slots = Collections.unmodifiableMap(indexed);
        this.candidates = Collections.unmodifiableList(new ArrayList<ToolCandidate>(
                candidates == null ? Collections.<ToolCandidate>emptyList() : candidates));
    }

    /** @return 零库存遍历的可信空覆盖。 */
    public static ToolSwapInventorySnapshot none() {
        return new ToolSwapInventorySnapshot(true, false,
                Collections.<SlotSnapshot>emptyList(), Collections.<ToolCandidate>emptyList());
    }

    /** @return 一次原子采样失败后的显式不可信结果。 */
    public static ToolSwapInventorySnapshot untrusted() {
        return new ToolSwapInventorySnapshot(false, false,
                Collections.<SlotSnapshot>emptyList(), Collections.<ToolCandidate>emptyList());
    }

    /** 创建只覆盖账本双槽的可信快照。 */
    public static ToolSwapInventorySnapshot protectedSlots(List<SlotSnapshot> slots) {
        return new ToolSwapInventorySnapshot(true, false, slots, Collections.<ToolCandidate>emptyList());
    }

    public boolean isTrusted() {
        return trusted;
    }

    public boolean isFullCandidateScan() {
        return trusted && fullCandidateScan;
    }

    public boolean covers(int slot) {
        return slots.containsKey(Integer.valueOf(slot));
    }

    public SlotSnapshot slot(int index) {
        return slots.get(Integer.valueOf(index));
    }

    public List<ToolCandidate> candidates() {
        return candidates;
    }

    public ToolCandidate candidateAt(int slot) {
        for (ToolCandidate candidate : candidates) {
            if (candidate != null && candidate.slot() == slot) {
                return candidate;
            }
        }
        return null;
    }
}
