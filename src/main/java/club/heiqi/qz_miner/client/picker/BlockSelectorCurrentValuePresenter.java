package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;

/** 将对象组 members 当前值投影为方块名称、图标与 canonical 摘要。 */
final class BlockSelectorCurrentValuePresenter implements CurrentValuePresenter {
    private final Map<String, BlockCandidate> candidates;
    private final VisualAdapter visualAdapter;

    /** 固化候选索引与视觉适配器。 */
    BlockSelectorCurrentValuePresenter(List<BlockCandidate> source, VisualAdapter visualAdapter) {
        Map<String, BlockCandidate> snapshot = new LinkedHashMap<String, BlockCandidate>();
        for (BlockCandidate candidate : source) snapshot.put(candidate.registry(), candidate);
        this.candidates = Collections.unmodifiableMap(snapshot);
        this.visualAdapter = visualAdapter;
    }

    @Override
    public Presentation present(Object value) {
        Object raw = first(value);
        if (!(raw instanceof String)) return new Presentation(String.valueOf(raw), "", null);
        String text = (String) raw;
        try {
            ObjectGroupSelector selector = ObjectGroupParser.parseSelector(text);
            BlockCandidate candidate = candidates.get(selector.registry());
            if (candidate == null) return new Presentation(text, text, null);
            SearchPickerData.Candidate visual = new SearchPickerData.Candidate(candidate.registry(),
                    candidate.localizedName(), Collections.<SearchPickerData.Variant>emptyList());
            return new Presentation(candidate.localizedName(), selector.canonical(),
                    visualAdapter.candidateImage(visual));
        } catch (IllegalArgumentException invalid) {
            return new Presentation(text, text, null);
        }
    }

    private static Object first(Object value) {
        if (value instanceof List && !((List<?>) value).isEmpty()) return ((List<?>) value).get(0);
        return value == null ? "" : value;
    }
}
