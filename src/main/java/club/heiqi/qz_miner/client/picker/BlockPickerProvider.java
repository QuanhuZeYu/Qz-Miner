package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.SearchPickerPresentation;
import club.heiqi.config.ui.editor.ValueEditorProvider;
import club.heiqi.config.ui.editor.VisualAdapter;

/** 方块选择器 Provider；构造时固化索引、搜索函数、Codec 和视觉适配器。 */
public final class BlockPickerProvider implements ValueEditorProvider {
    public static final String ID = "qz_miner:block-selector";
    private final Codec codec;
    private final VisualAdapter visualAdapter;
    private final SearchFunction searchFunction;
    private final SearchPickerPresentation presentation;
    private final CurrentValuePresenter currentValuePresenter;

    public BlockPickerProvider(List<BlockCandidate> source) {
        List<BlockCandidate> snapshot = Collections.unmodifiableList(new ArrayList<BlockCandidate>(source));
        BlockSearchIndex index = new BlockSearchIndex(snapshot);
        codec = new ObjectGroupPickerCodec();
        visualAdapter = new BlockPickerVisualAdapter(snapshot);
        searchFunction = (query, limit) -> convert(index.search(query, Integer.MAX_VALUE));
        currentValuePresenter = new BlockSelectorCurrentValuePresenter(snapshot, visualAdapter);
        presentation = SearchPickerPresentation.builder()
                .title("添加方块")
                .placeholder("搜索方块名称或 registry id")
                .all("全部状态")
                .single("指定一个状态")
                .multiple("指定多个状态")
                .cancel("取消")
                .confirm("添加到组")
                .empty("没有找到方块")
                .resultSummaryFormatter(count -> count + " 个结果")
                .truncated("结果已截断，请继续缩小搜索范围")
                .decodeError("无法读取当前方块规则，原规则未变")
                .searchError("搜索方块时发生错误，原规则未变")
                .encodeError("添加方块时发生错误，原规则未变")
                .build();
    }

    public String id() { return ID; }
    public Codec codec() { return codec; }
    public VisualAdapter visualAdapter() { return visualAdapter; }
    public SearchFunction searchFunction() { return searchFunction; }
    public SearchPickerPresentation presentation() { return presentation; }
    public CurrentValuePresenter currentValuePresenter() { return currentValuePresenter; }

    private static SearchPickerData.SearchResult convert(BlockSearchIndex.Result result) {
        List<SearchPickerData.Candidate> candidates = new ArrayList<SearchPickerData.Candidate>();
        for (BlockCandidate candidate : result.candidates()) {
            List<SearchPickerData.Variant> variants = new ArrayList<SearchPickerData.Variant>();
            for (BlockVariant variant : candidate.variants()) {
                String key = candidate.registry() + "@" + variant.metadata();
                variants.add(new SearchPickerData.Variant(key, variant.name() + " (" + variant.metadata() + ")"));
            }
            candidates.add(new SearchPickerData.Candidate(candidate.registry(), candidate.localizedName(), variants));
        }
        return new SearchPickerData.SearchResult(candidates);
    }
}
