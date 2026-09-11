package club.heiqi.qz_miner.client.picker;

import java.util.List;

import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;

/** 将对象组 members 当前值投影为方块名称、图标与 canonical 摘要。 */
final class BlockSelectorCurrentValuePresenter implements CurrentValuePresenter {
    private static final String INVALID_TITLE = "无法读取当前方块规则";
    private static final String INVALID_SUMMARY = "请通过高级原始规则修正或删除";

    private final BlockPickerCandidateSource candidateSource;
    private final VisualAdapter visualAdapter;

    /** 直接复用进程级候选源（O(1) 定位 + 至多一次分片物化，不再持有构造期索引副本）。 */
    BlockSelectorCurrentValuePresenter(BlockPickerCandidateSource candidateSource, VisualAdapter visualAdapter) {
        this.candidateSource = candidateSource;
        this.visualAdapter = visualAdapter;
    }

    @Override
    public Presentation present(Object value) {
        Object raw = first(value);
        if (!(raw instanceof String)) return new Presentation(String.valueOf(raw), "", null);
        String text = (String) raw;
        try {
            ObjectGroupSelector selector = ObjectGroupParser.parseSelector(text);
            SearchPickerData.Candidate candidate = candidateSource.exact(selector.registry());
            if (candidate == null) {
                String canonical = selector.canonical();
                return new Presentation(canonical, canonical, null);
            }
            return new Presentation(candidate.label(), selector.canonical(),
                    visualAdapter.candidateImage(candidate));
        } catch (IllegalArgumentException invalid) {
            return new Presentation(INVALID_TITLE, INVALID_SUMMARY, null);
        }
    }

    private static Object first(Object value) {
        if (value instanceof List && !((List<?>) value).isEmpty()) return ((List<?>) value).get(0);
        return value == null ? "" : value;
    }
}
