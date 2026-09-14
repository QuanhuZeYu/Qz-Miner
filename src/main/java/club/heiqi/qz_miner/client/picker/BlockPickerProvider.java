package club.heiqi.qz_miner.client.picker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.CandidateSourceValueEditorProvider;
import club.heiqi.config.ui.editor.CategorizedValueEditorProvider;
import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.PickerCandidateSource;
import club.heiqi.config.ui.editor.PickerIconSource;
import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.SearchPickerPanelPresentation;
import club.heiqi.config.ui.editor.SearchPickerPresentation;
import club.heiqi.config.ui.editor.VisualAdapter;

/**
 * 方块选择器 Provider：<b>只固化惰性候选源的引用</b>，构造期零枚举、零全量预转换。
 *
 * <p>契约出处：（ADR 原稿在工作站、不在仓内） §1.2/§1.4/§1.7（D-1..D-7）；（改造设计原稿在工作站、不在仓内）（M3-M6）。</p>
 *
 * <p><b>构造期删除清单（ADR §1.7）</b>：</p>
 * <ul>
 *   <li>D-1 {@code browseResult = convertCandidates(snapshot)} —— 删除；空查询由
 *       {@link PickerCandidateSource#page} 按窗口切片产出；</li>
 *   <li>D-2 每次非空查询的 {@code convertCandidates(searchIndex.search(...))} —— 删除；
 *       搜索只给命中序，转换随窗口物化；</li>
 *   <li>D-3 构造期索引里的变体名拼串 —— 迁到 {@link BlockPickerNameIndex}（首个文本查询时构建一次）；</li>
 *   <li>D-4 构造期全量分类快照 —— 迁到 {@link BlockRegistrySnapshot#modCategories()}（清单级前缀统计，随清单代际重建）；</li>
 *   <li>D-5 注册期枚举 —— 删除（{@link ObjectGroupPickerRegistration} 只注册引用）；</li>
 *   <li>D-6 无界图标缓存 —— 删除（缓存归 UILib {@code PickerIconCache}）；</li>
 *   <li>D-7 {@code BlockSearchIndex} 的 65 硬夹 —— 删除；命中数是真值，窗口切片按调用方给出的
 *       {@code WindowRequest(offset, limit)} 惰性分页（搜索窗口上限概念已整链移除，本侧不再有任何
 *       把命中数夹到上限的代码路径）。</li>
 * </ul>
 *
 * <p><b>兼容壳</b>：{@link #searchFunction()} 仍返回非 null（{@code Registry.register} 要求），
 * 但它与候选源走<b>同一条惰性路径</b>（不再有任何全量预转换）；SPI 路径下 UILib 直接调用
 * {@link #candidateSource()}，该壳只服务未实现新 SPI 的外部消费面（过渡态 T-1）。旧全量路径的外部
 * 调用方给出有限 {@code limit} 时，兼容壳仍按该预算切片并<b>如实</b>透传 {@code truncated()}；
 * SPI 路径不经过该预算，故搜索 lane 不产生截断。</p>
 *
 * <p><b>截断文案通道</b>：{@code presentation.truncated()} 与 {@code panelPresentation.truncatedResults()}
 * 两个键<b>保留</b>（旧全量路径的外部调用方给有限 limit 时仍须如实提示），但在 SPI 路径不再触发——
 * 搜索 lane 走「真实命中数 + 惰性分页」，无上限即无截断，滚动态提示由 {@code scrollHint} 承担。</p>
 */
public final class BlockPickerProvider implements CategorizedValueEditorProvider, CandidateSourceValueEditorProvider {
    public static final String ID = "qz_miner:block-selector";

    private final BlockPickerCandidateSource candidateSource;
    private final PickerIconSource iconSource;
    private final Codec codec;
    private final VisualAdapter visualAdapter;
    private final SearchFunction searchFunction;
    private final SearchPickerPresentation presentation;
    private final SearchPickerPanelPresentation panelPresentation;
    private final CurrentValuePresenter currentValuePresenter;

    /** 生产装配：进程级单例候选源（注册不读注册表、不物化候选）。 */
    public BlockPickerProvider() {
        this(BlockPickerCandidateSource.getInstance());
    }

    /**
     * 可注入候选源（测试用）。
     *
     * @param candidateSource 候选源（非 null）
     */
    public BlockPickerProvider(BlockPickerCandidateSource candidateSource) {
        if (candidateSource == null) {
            throw new IllegalArgumentException("candidateSource must not be null");
        }
        this.candidateSource = candidateSource;
        this.iconSource = new BlockPickerIconSource(candidateSource);
        ObjectGroupPickerCodec pickerCodec = new ObjectGroupPickerCodec();
        codec = pickerCodec;
        visualAdapter = new BlockPickerVisualAdapter(iconSource);
        // 兼容壳（T-1 外部消费面）：与 SPI 路径同一惰性源，仅按调用方给出的预算切窗口并如实透传
        // truncated（SPI 路径不经过此预算，搜索 lane 恒不截断）。
        searchFunction = (query, limit) -> {
            if (limit <= 0) {
                return SearchPickerData.SearchResult.empty();
            }
            PickerQuery pickerQuery = PickerQuery.text(query, 0, null);
            int hits = candidateSource.matchCount(pickerQuery);
            int window = pickerQuery.isBrowse() ? hits : Math.min(hits, limit);
            List<SearchPickerData.Candidate> slice = candidateSource.page(pickerQuery, 0, window);
            return SearchPickerData.SearchResult.of(slice, hits > window);
        };
        currentValuePresenter = new BlockSelectorCurrentValuePresenter(candidateSource, visualAdapter);
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
                // P5 新增键（ADR §1.5 三态空态；P5 规格 §3.3）：浏览 + 分类过滤为 0 与变体筛选无匹配
                // 必须与「搜索无命中」区分，只读提示必须点出可勾选的模式名（与本 builder 的 selected 文案一致）。
                .emptyCategoryResults("该分类下暂无方块")
                .emptyVariants("没有匹配的状态")
                .modeReadOnlyHint("切换到「指定状态」后可勾选")
                .edit("编辑")
                .remove("删除")
                .errorSeverity("错误")
                .invalidIssue("无效")
                .warningSeverity("警告")
                .duplicateIssue("重复")
                // D5（P5 第三轮）：成员徽章 hover 原因模板（severity/issue/id 由 UILib 填充，raw 非空时自动追加）。
                .memberIssueReasonPattern("{severity} · {issue} · ID：{id}")
                .currentMemberPrimaryFormatter(this::formatCurrentMemberPrimary)
                .currentMemberSecondaryFormatter(member -> formatCurrentMemberSecondary(member, pickerCodec))
                .resultSummaryFormatter(count -> count + " 个结果")
                // 保留键：SPI 路径无上限即无截断，仅旧全量路径（外部调用方给有限 limit）触发。
                .truncated("结果已截断，请继续缩小搜索范围")
                .decodeError("无法读取当前方块规则，原规则未变")
                .searchError("搜索方块时发生错误，原规则未变")
                .encodeError("添加方块时发生错误，原规则未变")
                .build();
        panelPresentation = SearchPickerPanelPresentation.builder()
                .panelTitle("选择方块")
                .categoryDimensions(Arrays.asList("按 Mod"))
                .categoryDimensionTitle("浏览分类")
                .allCategoryLabel("全部")
                .tooltipPrefix("ID: ")
                .emptyCategory("无可用分类")
                .variantPanelTitle("选择方块状态")
                .variantSearchPlaceholder("筛选状态")
                .back("返回")
                .close("关闭")
                .addMember("添加方块")
                // P5 新增键（P5 规格 §3.3）：全部为面板侧新增区域的文案。键 = Presentation 访问器，
                // 任何一个漏注入都会让中文界面显示英文默认值（P6 阻塞项 B-4），故在此一次补齐，
                // 并由 BlockPickerProviderTest 的注入完整性守卫（反射枚举访问器）钉死。
                // 信息条：空闲态提示优先级 = 截断 > 键盘 > 滚动 > 悬停（ScenePickerPanel 内容 Owner 内唯一取值点）。
                // 保留键：SPI 路径恒不截断（无上限即无提示），旧全量路径仍如实置 truncated。
                .truncatedResults("结果已截断，请缩小搜索范围")
                .hoverHint("悬停查看完整名称与 ID")
                // 悬停态单行模板：id 已含 tooltipPrefix（"ID: "），中文语境用全角括号比 " · " 更易读。
                .infoBarIdPattern("{label}（{id}）")
                .alreadyConfiguredBadge("已在本规则中")
                // D4（P5 第四轮）：信息条点击复制稳定 ID 后的 ≤2s 反馈模板（{id} = 已写入剪贴板的稳定 key）。
                .infoBarCopiedPattern("已复制 ID：{id}")
                // 成员带模式横幅（LIST_MEMBERS 为 Miner 的默认绑定模式）。
                .memberAddingBanner("点击方块继续添加（Esc 结束）")
                .memberEditingBanner("正在编辑：{name}")
                .keyboardHint("方向键移动，回车选择")
                .scrollHint("滚动查看更多结果")
                .densityLabel("密度")
                .removedToast("已删除 {name}")
                .undoAction("撤销")
                .build();
    }

    public String id() { return ID; }
    public Codec codec() { return codec; }
    public VisualAdapter visualAdapter() { return visualAdapter; }
    public SearchFunction searchFunction() { return searchFunction; }
    public SearchPickerPresentation presentation() { return presentation; }
    public SearchPickerPanelPresentation panelPresentation() { return panelPresentation; }
    public CurrentValuePresenter currentValuePresenter() { return currentValuePresenter; }

    /** {@inheritDoc} SPI 路径：UILib 探测到非 null 即走查询式求值；面板按窗口几何对命中序惰性分页
     * （总量 = 真实命中数，任意 offset 可寻址），不产生截断。 */
    @Override
    public PickerCandidateSource candidateSource() { return candidateSource; }

    /** {@inheritDoc} 候选域图标源的唯一入口（候选级/变体级）；图标缓存归 UILib。 */
    @Override
    public PickerIconSource iconSource() { return iconSource; }

    /** {@inheritDoc} 分类导航行（维度 0 = 按 Mod）：清单级前缀统计，随清单代际重建。 */
    @Override
    public List<SearchPickerCategories.Category> categories() { return candidateSource.categories(0); }

    @Override
    public String categoryOf(String candidateKey) { return categoryOf(0, candidateKey); }

    @Override
    public int categoryDimensionCount() { return 1; }

    @Override
    public List<SearchPickerCategories.Category> categories(int dimension) {
        return candidateSource.categories(dimension);
    }

    @Override
    public String categoryOf(int dimension, String candidateKey) {
        if (dimension < 0) throw new IllegalArgumentException("dimension must not be negative: " + dimension);
        if (dimension > 0) return null;
        return candidateSource.categoryOf(candidateKey);
    }

    /** 将成员选择格式化为本地化主名称，错误成员不暴露 raw。 */
    private String formatCurrentMemberPrimary(SearchPickerData.CurrentMember member) {
        if (member.selection() == null) return "无法读取当前方块规则";
        if (member.enumerated()) return member.candidate().label();
        SearchPickerData.Candidate candidate = candidateSource.exact(member.selection().candidateKey());
        return candidate == null ? member.selection().candidateKey() : candidate.label();
    }

    /** 将合法成员选择格式化为完整 canonical 补充信息。 */
    private static String formatCurrentMemberSecondary(SearchPickerData.CurrentMember member,
            ObjectGroupPickerCodec pickerCodec) {
        if (member.selection() == null) return "";
        return (String) pickerCodec.encodeMember(null, member.selection());
    }

    /** 空清单占位（诊断/测试）：不读取真实注册表。 */
    static List<SearchPickerCategories.Category> noCategories() {
        return Collections.emptyList();
    }
}
