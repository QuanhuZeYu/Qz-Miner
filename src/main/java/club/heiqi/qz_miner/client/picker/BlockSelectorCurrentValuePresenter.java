package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;

/** 将对象组 members 当前值投影为方块名称、图标与 canonical 摘要。 */
final class BlockSelectorCurrentValuePresenter implements CurrentValuePresenter {
    private static final String INVALID_TITLE = "无法读取当前方块规则";
    private static final String INVALID_SUMMARY = "请通过高级原始规则修正或删除";

    private final Map<String, BlockCandidate> candidates;
    private final VisualAdapter visualAdapter;

    /** 直接复用 Provider 的共享候选索引（同一份不可变快照，不再重复建索引）。 */
    BlockSelectorCurrentValuePresenter(Map<String, BlockCandidate> candidates, VisualAdapter visualAdapter) {
        this.candidates = candidates;
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
            if (candidate == null) {
                String canonical = selector.canonical();
                return new Presentation(canonical, canonical, null);
            }
            SearchPickerData.Candidate visual = new SearchPickerData.Candidate(candidate.registry(),
                    candidate.localizedName(), Collections.<SearchPickerData.Variant>emptyList());
            return new Presentation(candidate.localizedName(), selector.canonical(),
                    visualAdapter.candidateImage(visual));
        } catch (IllegalArgumentException invalid) {
            return new Presentation(INVALID_TITLE, INVALID_SUMMARY, null);
        }
    }

    private static Object first(Object value) {
        if (value instanceof List && !((List<?>) value).isEmpty()) return ((List<?>) value).get(0);
        return value == null ? "" : value;
    }
}
