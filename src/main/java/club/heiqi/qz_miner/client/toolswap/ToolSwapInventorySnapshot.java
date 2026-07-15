package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.qz_miner.toolswap.ToolCandidate;

/** 纯核心所需的 0..35 槽角色与工具候选快照。 */
public final class ToolSwapInventorySnapshot {

    private final Map<Integer, SlotSnapshot> slots;
    private final List<ToolCandidate> candidates;

    public ToolSwapInventorySnapshot(List<SlotSnapshot> slots, List<ToolCandidate> candidates) {
        Map<Integer, SlotSnapshot> indexed = new LinkedHashMap<Integer, SlotSnapshot>();
        if (slots != null) {
            for (SlotSnapshot slot : slots) {
                if (slot == null || indexed.put(Integer.valueOf(slot.slot()), slot) != null) {
                    throw new IllegalArgumentException("slot snapshots must be non-null and unique");
                }
            }
        }
        this.slots = Collections.unmodifiableMap(indexed);
        this.candidates = Collections.unmodifiableList(new ArrayList<ToolCandidate>(
                candidates == null ? Collections.<ToolCandidate>emptyList() : candidates));
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
