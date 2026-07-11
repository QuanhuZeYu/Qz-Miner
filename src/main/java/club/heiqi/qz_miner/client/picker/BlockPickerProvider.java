package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.ValueEditorProvider;
import club.heiqi.config.ui.editor.VisualAdapter;

/** 方块选择器 Provider；构造时固化索引、搜索函数、Codec 和视觉适配器。 */
public final class BlockPickerProvider implements ValueEditorProvider {
    public static final String ID = "qz_miner:block-selector";
    private final Codec codec;
    private final VisualAdapter visualAdapter;
    private final SearchFunction searchFunction;

    public BlockPickerProvider(List<BlockCandidate> source) {
        List<BlockCandidate> snapshot = Collections.unmodifiableList(new ArrayList<BlockCandidate>(source));
        BlockSearchIndex index = new BlockSearchIndex(snapshot);
        codec = new ObjectGroupPickerCodec();
        visualAdapter = new BlockPickerVisualAdapter(snapshot);
        searchFunction = (query, limit) -> convert(index.search(query, expandedLimit(limit)), limit);
    }

    public String id() { return ID; }
    public Codec codec() { return codec; }
    public VisualAdapter visualAdapter() { return visualAdapter; }
    public SearchFunction searchFunction() { return searchFunction; }

    private static int expandedLimit(int requestedLimit) {
        int bounded = Math.max(0, Math.min(SearchPickerData.MAX_RESULTS, requestedLimit));
        return bounded == 0 ? 0 : bounded + 1;
    }

    private static SearchPickerData.SearchResult convert(BlockSearchIndex.Result result, int requestedLimit) {
        List<SearchPickerData.Candidate> candidates = new ArrayList<SearchPickerData.Candidate>();
        for (BlockCandidate candidate : result.candidates()) {
            List<SearchPickerData.Variant> variants = new ArrayList<SearchPickerData.Variant>();
            for (BlockVariant variant : candidate.variants()) {
                String key = candidate.registry() + "@" + variant.metadata();
                variants.add(new SearchPickerData.Variant(key, variant.name() + " (" + variant.metadata() + ")"));
            }
            candidates.add(new SearchPickerData.Candidate(candidate.registry(), candidate.localizedName(), variants));
        }
        SearchPickerData.SearchResult converted = new SearchPickerData.SearchResult(candidates);
        int limit = Math.max(0, Math.min(SearchPickerData.MAX_RESULTS, requestedLimit));
        return converted.limitedTo(limit);
    }
}
