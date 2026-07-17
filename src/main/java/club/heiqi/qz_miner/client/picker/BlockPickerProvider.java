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
        ObjectGroupPickerCodec pickerCodec = new ObjectGroupPickerCodec();
        codec = pickerCodec;
        visualAdapter = new BlockPickerVisualAdapter(snapshot);
        searchFunction = (query, limit) -> convert(index.search(query, Integer.MAX_VALUE));
        currentValuePresenter = new BlockSelectorCurrentValuePresenter(snapshot, visualAdapter);
        presentation = SearchPickerPresentation.builder()
                .title("添加方块")
                .placeholder("搜索方块名称或 registry id")
                .all("全部状态")
                .selected("指定状态")
                .unavailableVariant("当前未枚举状态 ({key})")
                .cancel("取消")
                .confirm("添加到组")
                .empty("没有找到方块")
                .currentMembersTitle("当前方块规则")
                .searchResultsTitle("搜索结果")
                .manage("管理规则")
                .configuredEmpty("尚未配置")
                .configuredSummaryFormatter(count -> "已配置" + count + "条")
                .invalidSummaryFormatter(count -> "无效" + count)
                .duplicateSummaryFormatter(count -> "重复" + count)
                .advancedRaw("高级自定义")
                .emptyCurrentMembers("当前无规则")
                .emptySearchResults("无匹配结果")
                .edit("编辑")
                .remove("删除")
                .cancelRemove("取消")
                .confirmRemove("确认删除")
                .errorSeverity("错误")
                .invalidIssue("无效")
                .warningSeverity("警告")
                .duplicateIssue("重复")
                .currentMemberPrimaryFormatter(BlockPickerProvider::formatCurrentMemberPrimary)
                .currentMemberSecondaryFormatter(member -> formatCurrentMemberSecondary(member, pickerCodec))
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

    /** 将成员选择格式化为本地化主名称，错误成员不暴露 raw。 */
    private static String formatCurrentMemberPrimary(SearchPickerData.CurrentMember member) {
        if (member.selection() == null) return "无法读取当前方块规则";
        return member.enumerated() ? member.candidate().label() : member.selection().candidateKey();
    }

    /** 将合法成员选择格式化为完整 canonical 补充信息。 */
    private static String formatCurrentMemberSecondary(SearchPickerData.CurrentMember member,
            ObjectGroupPickerCodec pickerCodec) {
        if (member.selection() == null) return "";
        return (String) pickerCodec.encodeMember(null, member.selection());
    }

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
