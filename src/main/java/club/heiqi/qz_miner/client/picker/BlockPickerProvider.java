package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.creativetab.CreativeTabs;

import club.heiqi.config.ui.editor.CategorizedValueEditorProvider;
import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.SearchPickerPanelPresentation;
import club.heiqi.config.ui.editor.SearchPickerPresentation;
import club.heiqi.config.ui.editor.VisualAdapter;

/** 方块选择器 Provider；构造时固化索引、空查询全量浏览快照、搜索函数、双维度分类快照、Codec 和视觉适配器。 */
public final class BlockPickerProvider implements CategorizedValueEditorProvider {
    public static final String ID = "qz_miner:block-selector";
    /** 无有效创造栏（tabAllSearch/null/捕获失败）候选的稳定分类 key（含冒号避免与 tab 标签冲突）。 */
    public static final String OTHER_TAB_KEY = "qz_miner:other";
    /** 「其他」分类展示文案。 */
    public static final String OTHER_TAB_LABEL = "其他";

    private final Codec codec;
    private final VisualAdapter visualAdapter;
    private final SearchFunction searchFunction;
    private final SearchPickerData.SearchResult browseResult;
    private final SearchPickerPresentation presentation;
    private final SearchPickerPanelPresentation panelPresentation;
    private final CurrentValuePresenter currentValuePresenter;
    private final Map<String, BlockCandidate> byRegistry;
    private final List<SearchPickerCategories.Category> tabCategories;
    private final List<SearchPickerCategories.Category> modCategories;

    public BlockPickerProvider(List<BlockCandidate> source) {
        List<BlockCandidate> snapshot = Collections.unmodifiableList(new ArrayList<BlockCandidate>(source));
        Map<String, BlockCandidate> index = new LinkedHashMap<String, BlockCandidate>();
        for (BlockCandidate candidate : snapshot) index.put(candidate.registry(), candidate);
        byRegistry = Collections.unmodifiableMap(index);
        tabCategories = buildTabCategories(snapshot);
        modCategories = buildModCategories(snapshot);
        BlockSearchIndex searchIndex = new BlockSearchIndex(snapshot);
        browseResult = convertCandidates(snapshot);
        ObjectGroupPickerCodec pickerCodec = new ObjectGroupPickerCodec();
        codec = pickerCodec;
        visualAdapter = new BlockPickerVisualAdapter(snapshot);
        // 空查询是分类浏览模式：面板据此渲染全部候选并派生分类计数；
        // 非空查询仍走确定性搜索索引。
        searchFunction = (query, limit) -> query == null || query.trim().isEmpty()
                ? browseResult
                : convertCandidates(searchIndex.search(query, Integer.MAX_VALUE).candidates());
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
                .currentMemberPrimaryFormatter(this::formatCurrentMemberPrimary)
                .currentMemberSecondaryFormatter(member -> formatCurrentMemberSecondary(member, pickerCodec))
                .resultSummaryFormatter(count -> count + " 个结果")
                .truncated("结果已截断，请继续缩小搜索范围")
                .decodeError("无法读取当前方块规则，原规则未变")
                .searchError("搜索方块时发生错误，原规则未变")
                .encodeError("添加方块时发生错误，原规则未变")
                .build();
        panelPresentation = SearchPickerPanelPresentation.builder()
                .panelTitle("选择方块")
                .categoryDimensions(Arrays.asList("创造栏", "按 Mod"))
                .categoryDimensionTitle("浏览分类")
                .allCategoryLabel("全部")
                .tooltipPrefix("ID: ")
                .emptyCategory("无可用分类")
                .variantPanelTitle("选择方块状态")
                .variantSearchPlaceholder("筛选状态")
                .back("返回")
                .close("关闭")
                .addMember("添加方块")
                .build();
    }

    public String id() { return ID; }
    public Codec codec() { return codec; }
    public VisualAdapter visualAdapter() { return visualAdapter; }
    public SearchFunction searchFunction() { return searchFunction; }
    public SearchPickerPresentation presentation() { return presentation; }
    public SearchPickerPanelPresentation panelPresentation() { return panelPresentation; }
    public CurrentValuePresenter currentValuePresenter() { return currentValuePresenter; }

    @Override
    public List<SearchPickerCategories.Category> categories() { return tabCategories; }

    @Override
    public String categoryOf(String candidateKey) { return categoryOf(0, candidateKey); }

    @Override
    public int categoryDimensionCount() { return 2; }

    @Override
    public List<SearchPickerCategories.Category> categories(int dimension) {
        if (dimension < 0) throw new IllegalArgumentException("dimension must not be negative: " + dimension);
        if (dimension == 0) return tabCategories;
        if (dimension == 1) return modCategories;
        return Collections.emptyList();
    }

    @Override
    public String categoryOf(int dimension, String candidateKey) {
        if (dimension < 0) throw new IllegalArgumentException("dimension must not be negative: " + dimension);
        if (dimension > 1 || candidateKey == null) return null;
        BlockCandidate candidate = byRegistry.get(candidateKey);
        if (candidate == null) return null;
        if (dimension == 1) {
            String modId = candidate.modId();
            return modId == null || modId.isEmpty() ? null : modId;
        }
        String tab = candidate.creativeTab();
        return tab == null || tab.isEmpty() ? OTHER_TAB_KEY : tab;
    }

    /**
     * dim 0（创造栏）分类快照：按 creativeTabArray 顺序的本地化标签，静态 count 为注册时候选数；
     * 无有效创造栏的候选归入「其他」桶（仅在有此类候选时出现）。同名本地化标签合并计数。
     */
    private static List<SearchPickerCategories.Category> buildTabCategories(List<BlockCandidate> snapshot) {
        List<String> tabLabels = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        CreativeTabs[] tabs;
        try {
            tabs = CreativeTabs.creativeTabArray;
        } catch (RuntimeException e) {
            tabs = null;
        } catch (LinkageError e) {
            tabs = null;
        }
        if (tabs != null) {
            for (CreativeTabs tab : tabs) {
                if (tab == null || tab == CreativeTabs.tabAllSearch) continue;
                String label = translatedTabLabel(tab);
                if (label != null && seen.add(label)) tabLabels.add(label);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String label : tabLabels) counts.put(label, Integer.valueOf(0));
        int otherCount = 0;
        for (BlockCandidate candidate : snapshot) {
            String tabLabel = candidate.creativeTab();
            if (tabLabel == null || tabLabel.isEmpty()) {
                otherCount++;
            } else if (counts.containsKey(tabLabel)) {
                counts.put(tabLabel, Integer.valueOf(counts.get(tabLabel).intValue() + 1));
            }
        }
        List<SearchPickerCategories.Category> categories = new ArrayList<SearchPickerCategories.Category>();
        for (String label : tabLabels) {
            categories.add(new SearchPickerCategories.Category(label, label, counts.get(label).intValue()));
        }
        if (otherCount > 0) {
            categories.add(new SearchPickerCategories.Category(OTHER_TAB_KEY, OTHER_TAB_LABEL, otherCount));
        }
        return Collections.unmodifiableList(categories);
    }

    /** dim 1（Mod）分类快照：registry namespace 字典序，静态 count 为注册时候选数。 */
    private static List<SearchPickerCategories.Category> buildModCategories(List<BlockCandidate> snapshot) {
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (BlockCandidate candidate : snapshot) {
            String modId = candidate.modId();
            if (modId == null || modId.isEmpty()) continue;
            Integer current = counts.get(modId);
            counts.put(modId, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        List<SearchPickerCategories.Category> categories = new ArrayList<SearchPickerCategories.Category>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            categories.add(new SearchPickerCategories.Category(entry.getKey(), entry.getKey(),
                    entry.getValue().intValue()));
        }
        return Collections.unmodifiableList(categories);
    }

    /** 读取创造栏本地化标签；任何客户端本地化异常降级为 null。 */
    private static String translatedTabLabel(CreativeTabs tab) {
        try {
            String label = tab.getTranslatedTabLabel();
            return label == null || label.trim().isEmpty() ? null : label;
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /** 将成员选择格式化为本地化主名称，错误成员不暴露 raw。 */
    private String formatCurrentMemberPrimary(SearchPickerData.CurrentMember member) {
        if (member.selection() == null) return "无法读取当前方块规则";
        if (member.enumerated()) return member.candidate().label();
        BlockCandidate candidate = byRegistry.get(member.selection().candidateKey());
        return candidate == null ? member.selection().candidateKey() : candidate.localizedName();
    }

    /** 将合法成员选择格式化为完整 canonical 补充信息。 */
    private static String formatCurrentMemberSecondary(SearchPickerData.CurrentMember member,
            ObjectGroupPickerCodec pickerCodec) {
        if (member.selection() == null) return "";
        return (String) pickerCodec.encodeMember(null, member.selection());
    }

    private static SearchPickerData.SearchResult convertCandidates(List<BlockCandidate> blockCandidates) {
        List<SearchPickerData.Candidate> candidates = new ArrayList<SearchPickerData.Candidate>();
        for (BlockCandidate candidate : blockCandidates) {
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
