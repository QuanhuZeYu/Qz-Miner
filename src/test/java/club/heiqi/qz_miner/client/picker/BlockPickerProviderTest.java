package club.heiqi.qz_miner.client.picker;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;

import club.heiqi.config.ui.editor.CandidateSourceValueEditorProvider;
import club.heiqi.config.ui.editor.ListMemberCodec;
import club.heiqi.config.ui.editor.PickerCandidateSource;
import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.SearchPickerPanelPresentation;
import club.heiqi.config.ui.editor.SearchPickerPresentation;

/**
 * Provider 收敛后的 SPI 契约测试：构造期零枚举、浏览/搜索两条 lane 的窗口语义、
 * 兼容壳与 SPI 同源、分类与文案不变（ADR A-05/A-06/A5，设计 N4/N5）。
 */
public class BlockPickerProviderTest {

    @Test
    public void providerConstructionCapturesNothingAndSearchSharesOneLazySource() {
        Fixture fixture = new Fixture(new BlockCandidate("minecraft:stone", "Stone",
                Collections.singletonList(new BlockVariant(3, "Stone 3", null)), null));

        BlockPickerProvider provider = fixture.provider();

        Assert.assertEquals("构造期不得捕获清单", 0, fixture.snapshots.calls);
        Assert.assertEquals("构造期不得物化任何分片", 0, fixture.materializations);
        Assert.assertNotNull(provider.candidateSource());
        Assert.assertNotNull(provider.iconSource());
        Assert.assertSame(provider.candidateSource(), provider.candidateSource());

        Assert.assertEquals("minecraft:stone", provider.searchFunction().search("stone", 64)
                .candidates().get(0).key());
        Assert.assertEquals("minecraft:stone@3", provider.searchFunction().search("stone", 64)
                .candidates().get(0).variants().get(0).key());
        Assert.assertEquals("SPI 面与兼容壳必须同源", "minecraft:stone", provider.candidateSource()
                .exact("minecraft:stone").key());
    }

    @Test
    public void registrationCapturesReferenceWithoutEnumeratingCandidates() {
        Fixture fixture = new Fixture(
                new BlockCandidate("minecraft:stone", "Stone", Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("gt:copper", "Copper", Collections.<BlockVariant>emptyList(), null));
        Registry registry = new Registry();

        registry.register(new BlockPickerProvider(fixture.source));

        Assert.assertEquals("注册不得物化任何分片（getSubBlocks = 0）", 0, fixture.materializations);
        Assert.assertTrue("清单读取不得超过一次", fixture.snapshots.calls <= 1);
        CandidateSourceValueEditorProvider registered =
                (CandidateSourceValueEditorProvider) registry.find(BlockPickerProvider.ID);
        Assert.assertNotNull(registered);
        Assert.assertSame("注册只固化惰性 source 引用", fixture.source, registered.candidateSource());
        Assert.assertNotNull(registered.iconSource());
        Assert.assertEquals(64, registered.searchMaxItems());
    }

    @Test
    public void emptyQueryReturnsBrowseWindowForCategoryBrowsing() {
        Fixture fixture = new Fixture(
                new BlockCandidate("minecraft:stone", "minecraft", "建筑方块", "Stone",
                        Collections.singletonList(new BlockVariant(3, "Stone 3", null)), null),
                new BlockCandidate("minecraft:dirt", "minecraft", "建筑方块", "Dirt",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("gt:copper", "gt", null, "Copper",
                        Collections.<BlockVariant>emptyList(), null));
        PickerCandidateSource source = fixture.provider().candidateSource();

        PickerQuery browse = PickerQuery.browse(0, null);
        Assert.assertEquals(3, source.matchCount(browse));
        List<SearchPickerData.Candidate> window = source.page(browse, 0, 64);
        Assert.assertEquals(3, window.size());
        Assert.assertEquals("minecraft:stone", window.get(0).key());
        Assert.assertEquals("minecraft:stone@3", window.get(0).variants().get(0).key());

        Assert.assertTrue("空白文本 = 浏览 lane", PickerQuery.text("  ", 0, null).isBrowse());
        Assert.assertEquals(3, fixture.provider().candidateSource()
                .matchCount(PickerQuery.text("  ", 0, null)));
        Assert.assertEquals(1, fixture.provider().candidateSource()
                .matchCount(PickerQuery.text("copper", 0, null)));
        Assert.assertEquals("兼容壳浏览 lane 仍返回全量", 3,
                fixture.provider().searchFunction().search("", 64).candidates().size());
    }

    /**
     * A5 改写：上限 64 由 UILib 装配层（{@code SearchPickerSpec.maxItems()}）传入，
     * 命中数为真值、{@code truncated} 为真值透传，Miner 侧不再有 65 硬夹与截断探针项。
     */
    @Test
    public void completeResultOverLimitKeepsExactlyRealEncodableCandidates() {
        Set<String> registries = new HashSet<String>();
        List<BlockCandidate> values = new ArrayList<BlockCandidate>();
        for (int i = 0; i < 65; i++) {
            String registry = "test:block_" + i;
            registries.add(registry);
            values.add(new BlockCandidate(registry, "Matching Block " + i,
                    Collections.<BlockVariant>emptyList(), null));
        }
        Fixture fixture = new Fixture(values.toArray(new BlockCandidate[values.size()]));
        BlockPickerProvider provider = fixture.provider();
        PickerCandidateSource source = provider.candidateSource();
        PickerQuery query = PickerQuery.text("matching", 0, null);

        Assert.assertEquals("命中数必须为真值（无 65 硬夹）", 65, source.matchCount(query));
        List<SearchPickerData.Candidate> window = source.page(query, 0, 64);
        Assert.assertEquals(64, window.size());
        Assert.assertTrue("truncated = matchCount > maxItems（真值透传）", source.matchCount(query) > 64);
        SearchPickerData.SearchResult shim = provider.searchFunction().search("matching", 64);
        Assert.assertEquals(64, shim.candidates().size());
        Assert.assertTrue("兼容壳同样透传截断真值", shim.truncated());

        for (SearchPickerData.Candidate candidate : window) {
            Assert.assertTrue(registries.contains(candidate.key()));
            Assert.assertNotEquals("qz_miner:truncated", candidate.key());
            ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
            Assert.assertEquals(Collections.singletonList(candidate.key() + "@*"), codec.encode(
                    Collections.emptyList(),
                    new SearchPickerData.Selection(candidate.key(), SearchPickerData.SelectionMode.ALL,
                            Collections.<String>emptyList())));
        }
    }

    @Test
    public void currentValuePresenterUsesSafeMalformedCopyAndCanonicalValidValues() {
        BlockPickerProvider provider = new Fixture(new BlockCandidate("minecraft:stone", "Stone",
                Collections.<BlockVariant>emptyList(), null)).provider();

        club.heiqi.config.ui.editor.CurrentValuePresenter.Presentation valid =
                provider.currentValuePresenter().present(Collections.singletonList("minecraft:stone@03"));
        Assert.assertEquals("Stone", valid.title());
        Assert.assertEquals("minecraft:stone@3", valid.summary());
        club.heiqi.config.ui.editor.CurrentValuePresenter.Presentation unknown =
                provider.currentValuePresenter().present(Collections.singletonList("missing:block@[8,4]"));
        Assert.assertEquals("missing:block@[4,8]", unknown.title());
        Assert.assertEquals("missing:block@[4,8]", unknown.summary());

        String malformedRaw = "not a selector secret raw";
        club.heiqi.config.ui.editor.CurrentValuePresenter.Presentation invalid =
                provider.currentValuePresenter().present(Collections.singletonList(malformedRaw));
        Assert.assertEquals("无法读取当前方块规则", invalid.title());
        Assert.assertEquals("请通过高级原始规则修正或删除", invalid.summary());
        Assert.assertNull(invalid.image());
        Assert.assertFalse(invalid.title().contains(malformedRaw));
        Assert.assertFalse(invalid.summary().contains(malformedRaw));
    }

    @Test
    public void presentationUsesCompleteChinesePlayerCopy() {
        SearchPickerPresentation text = new Fixture().provider().presentation();
        Assert.assertEquals("添加方块", text.title());
        Assert.assertEquals("搜索方块名称或 registry id", text.placeholder());
        Assert.assertEquals("全部状态", text.all());
        Assert.assertEquals("指定状态", text.selected());
        Assert.assertEquals("当前未枚举状态 (minecraft:stone@7)",
                text.unavailableVariant("minecraft:stone@7"));
        Assert.assertEquals("取消", text.cancel());
        Assert.assertEquals("添加到组", text.confirm());
        Assert.assertEquals("没有找到方块", text.empty());
        Assert.assertEquals("当前方块规则", text.currentMembersTitle());
        Assert.assertEquals("当前方块规则 (2)", text.currentMembersTitle(2));
        Assert.assertEquals("搜索结果", text.searchResultsTitle());
        Assert.assertEquals("搜索结果 (3)", text.searchResultsTitle(3));
        Assert.assertEquals("管理规则", text.manage());
        Assert.assertEquals("尚未配置", text.configuredSummary(0));
        Assert.assertEquals("已配置4条", text.configuredSummary(4));
        Assert.assertEquals("无效2 · 重复3", text.memberIssueSummary(2, 3));
        Assert.assertEquals("已配置5条 · 无效2 · 重复3", text.configuredSummary(5, 2, 3));
        Assert.assertEquals("高级自定义", text.advancedRaw());
        Assert.assertEquals("当前无规则", text.emptyCurrentMembers());
        Assert.assertEquals("无匹配结果", text.emptySearchResults());
        // P6/B-4：P5 新增三态空态与只读提示键（ADR §1.5 R-05；P5 规格 §3.3）。
        Assert.assertEquals("该分类下暂无方块", text.emptyCategoryResults());
        Assert.assertEquals("没有匹配的状态", text.emptyVariants());
        Assert.assertEquals("切换到「指定状态」后可勾选", text.modeReadOnlyHint());
        Assert.assertEquals("编辑", text.edit());
        Assert.assertEquals("删除", text.remove());
        Assert.assertEquals("错误/无效", text.invalidMemberBadge());
        Assert.assertEquals("警告/重复", text.duplicateMemberBadge());
        Assert.assertEquals("3 个结果", text.resultSummary(3));
        Assert.assertEquals("结果已截断，请继续缩小搜索范围", text.truncated());
        Assert.assertTrue(text.decodeError().contains("原规则未变"));
        Assert.assertTrue(text.searchError().contains("原规则未变"));
        Assert.assertTrue(text.encodeError().contains("原规则未变"));
    }

    @Test
    public void unenumeratedMemberFallsBackToLocalizedRegistrySnapshot() {
        BlockPickerProvider provider = new Fixture(new BlockCandidate("minecraft:stone", "Stone",
                Collections.<BlockVariant>emptyList(), null)).provider();
        SearchPickerData.Selection selection = new ObjectGroupPickerCodec().decodeMember("minecraft:stone@7");
        SearchPickerData.CurrentMember unEnumeratedHit =
                new SearchPickerData.CurrentMember(4L, selection, null, false);
        Assert.assertEquals("Stone", provider.presentation().currentMemberPrimary(unEnumeratedHit));

        SearchPickerData.Selection missingSelection = new ObjectGroupPickerCodec().decodeMember("missing:block@[8,4]");
        SearchPickerData.CurrentMember unEnumeratedMiss =
                new SearchPickerData.CurrentMember(5L, missingSelection, null, false);
        Assert.assertEquals("missing:block", provider.presentation().currentMemberPrimary(unEnumeratedMiss));
    }

    @Test
    public void memberFormatterUsesLocalizedCanonicalUnknownCanonicalAndGenericMalformedCopy() {
        BlockPickerProvider provider = new Fixture(new BlockCandidate("minecraft:stone", "Stone",
                Collections.<BlockVariant>emptyList(), null)).provider();
        Assert.assertTrue(provider.codec() instanceof ListMemberCodec);
        SearchPickerData.Selection knownSelection = new ObjectGroupPickerCodec().decodeMember("minecraft:stone@03");
        SearchPickerData.Candidate knownCandidate = provider.candidateSource().exact("minecraft:stone");
        SearchPickerData.CurrentMember known =
                new SearchPickerData.CurrentMember(1L, knownSelection, knownCandidate, true);
        Assert.assertEquals("Stone", provider.presentation().currentMemberPrimary(known));
        Assert.assertEquals("minecraft:stone@3", provider.presentation().currentMemberSecondary(known));

        SearchPickerData.Selection unknownSelection = new ObjectGroupPickerCodec().decodeMember("missing:block@[8,4]");
        SearchPickerData.CurrentMember unknown =
                new SearchPickerData.CurrentMember(2L, unknownSelection, null, false);
        Assert.assertEquals("missing:block", provider.presentation().currentMemberPrimary(unknown));
        Assert.assertEquals("missing:block@[4,8]", provider.presentation().currentMemberSecondary(unknown));
        SearchPickerData.CurrentMember malformed = new SearchPickerData.CurrentMember(3L, null, null, false);
        Assert.assertEquals("无法读取当前方块规则", provider.presentation().currentMemberPrimary(malformed));
        Assert.assertEquals("", provider.presentation().currentMemberSecondary(malformed));
        Assert.assertFalse(provider.presentation().currentMemberPrimary(malformed).contains("not a selector"));
        Assert.assertFalse(provider.presentation().invalidMemberBadge().contains("not a selector"));
        Assert.assertEquals("警告/重复", provider.presentation().duplicateMemberBadge());
    }

    @Test
    public void missingImagesReturnNullSoUilibOwnsThePlaceholder() {
        BlockCandidate missing = new BlockCandidate("test:missing", "Missing",
                Collections.singletonList(new BlockVariant(2, "Missing Variant", null)), null);
        BlockPickerProvider provider = new Fixture(missing).provider();
        SearchPickerData.Candidate candidate = provider.candidateSource().exact("test:missing");

        Assert.assertNotNull(candidate);
        Assert.assertNull("A6：无代表栈 → 无图，占位下沉 UILib",
                provider.iconSource().candidateIcon("test:missing"));
        Assert.assertNull(provider.visualAdapter().candidateImage(candidate));
        Assert.assertNull(provider.visualAdapter().variantImage(candidate.variants().get(0)));
        Assert.assertNull(provider.visualAdapter().candidateImage(null));
        Assert.assertNull("未知 key 不产生图标", provider.iconSource().candidateIcon("test:absent"));
    }

    @Test
    public void realLitRedstoneOreSearchesByRegistryAndLocalizedNameAndEncodesBothModes() {
        BlockCandidate lit = BlockVariantMaterializer.materialize(
                "minecraft:lit_redstone_ore", Blocks.lit_redstone_ore);
        BlockPickerProvider provider = new Fixture(lit).provider();
        PickerCandidateSource source = provider.candidateSource();

        SearchPickerData.Candidate byRegistry = source.exact("minecraft:lit_redstone_ore");
        Assert.assertNotNull(byRegistry);
        Assert.assertEquals("minecraft:lit_redstone_ore@0", byRegistry.variants().get(0).key());
        PickerQuery byName = PickerQuery.text(lit.localizedName(), 0, null);
        Assert.assertEquals(1, source.matchCount(byName));
        Assert.assertEquals("minecraft:lit_redstone_ore", source.page(byName, 0, 1).get(0).key());

        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        Assert.assertEquals(Collections.singletonList("minecraft:lit_redstone_ore@*"), codec.encode(
                Collections.emptyList(), new SearchPickerData.Selection(byRegistry.key(),
                        SearchPickerData.SelectionMode.ALL, Collections.<String>emptyList())));
        Assert.assertEquals(Collections.singletonList("minecraft:lit_redstone_ore@0"), codec.encode(
                Collections.emptyList(), new SearchPickerData.Selection(byRegistry.key(),
                        SearchPickerData.SelectionMode.SELECTED,
                        Collections.singletonList(byRegistry.variants().get(0).key()))));
    }

    @Test
    public void categoriesExposeSingleModDimensionWithStableKeysAndCounts() {
        Fixture fixture = new Fixture(
                new BlockCandidate("minecraft:stone", "minecraft", null, "Stone",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("minecraft:dirt", "minecraft", "测试栏", "Dirt",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("gt:copper", "gt", "测试栏", "Copper",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("galacticraft:venus", "galacticraft", null, "Venus",
                        Collections.<BlockVariant>emptyList(), null));
        BlockPickerProvider provider = fixture.provider();

        Assert.assertEquals(1, provider.categoryDimensionCount());
        List<SearchPickerCategories.Category> dim0 = provider.categories(0);
        Assert.assertEquals(provider.categories(), provider.categories(0));
        Assert.assertEquals(Arrays.asList("galacticraft", "gt", "minecraft"), categoryKeys(dim0));
        Assert.assertEquals(2, SearchPickerCategories.find(dim0, "minecraft").count());
        Assert.assertEquals(1, SearchPickerCategories.find(dim0, "gt").count());
        Assert.assertEquals(1, SearchPickerCategories.find(dim0, "galacticraft").count());
        Assert.assertEquals(Collections.emptyList(), provider.categories(1));

        try {
            provider.categories(0).add(new SearchPickerCategories.Category("x", "y", 1));
            Assert.fail("categories(0) must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void categoryOfResolvesModDimensionAndUnknownKeys() {
        Fixture fixture = new Fixture(
                new BlockCandidate("minecraft:stone", "minecraft", null, "Stone",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("minecraft:dirt", "minecraft", "测试栏", "Dirt",
                        Collections.<BlockVariant>emptyList(), null),
                new BlockCandidate("legacy:bare", null, null, "Bare",
                        Collections.<BlockVariant>emptyList(), null));
        BlockPickerProvider provider = fixture.provider();

        Assert.assertEquals("minecraft", provider.categoryOf("minecraft:stone"));
        Assert.assertEquals("minecraft", provider.categoryOf(0, "minecraft:stone"));
        Assert.assertEquals("minecraft", provider.categoryOf(0, "minecraft:dirt"));
        // D-4：分类来自清单级 namespace 前缀统计（不再读 candidate 的显式 modId 字段）
        Assert.assertEquals("legacy", provider.categoryOf(0, "legacy:bare"));
        Assert.assertNull(provider.categoryOf(0, "missing:block"));
        Assert.assertNull(provider.categoryOf(0, null));
        Assert.assertNull(provider.categoryOf(1, "minecraft:stone"));
        Assert.assertEquals(Collections.emptyList(), provider.categories(2));
        Assert.assertNull(provider.categoryOf(2, "minecraft:stone"));
        try {
            provider.categories(-1);
            Assert.fail("categories(-1) must throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            provider.categoryOf(-1, "minecraft:stone");
            Assert.fail("categoryOf(-1, ...) must throw");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void panelPresentationUsesCompleteChineseCopy() {
        SearchPickerPanelPresentation text = new Fixture().provider().panelPresentation();
        Assert.assertEquals("选择方块", text.panelTitle());
        Assert.assertEquals(Arrays.asList("按 Mod"), text.categoryDimensions());
        Assert.assertEquals("浏览分类", text.categoryDimensionTitle());
        Assert.assertEquals("全部", text.allCategoryLabel());
        Assert.assertEquals("ID: ", text.tooltipPrefix());
        Assert.assertEquals("无可用分类", text.emptyCategory());
        Assert.assertEquals("选择方块状态", text.variantPanelTitle());
        Assert.assertEquals("筛选状态", text.variantSearchPlaceholder());
        Assert.assertEquals("返回", text.back());
        Assert.assertEquals("关闭", text.close());
        Assert.assertEquals("添加方块", text.addMember());
        // P6/B-4：P5 新增的 11 个面板键（含信息条单行模板）；键名与语义出处 = P5 规格 §3.3 与
        // P5-实现记录 §10.2「键 → 显示位置 → 触发条件」表。
        Assert.assertEquals("结果已截断，请缩小搜索范围", text.truncatedResults());
        Assert.assertEquals("悬停查看完整名称与 ID", text.hoverHint());
        Assert.assertEquals("石头（ID: minecraft:stone）",
                text.infoBarIdLabel("石头", "ID: minecraft:stone"));
        Assert.assertEquals("已在本规则中", text.alreadyConfiguredBadge());
        Assert.assertEquals("点击方块继续添加（Esc 结束）", text.memberAddingBanner());
        Assert.assertEquals("正在编辑：石头", text.memberEditingBanner("石头"));
        Assert.assertEquals("正在编辑：", text.memberEditingBanner(null));
        Assert.assertEquals("方向键移动，回车选择", text.keyboardHint());
        Assert.assertEquals("滚动查看更多结果", text.scrollHint());
        Assert.assertEquals("密度", text.densityLabel());
        Assert.assertEquals("已删除 石头", text.removedToast("石头"));
        Assert.assertEquals("已删除 ", text.removedToast(null));
        Assert.assertEquals("撤销", text.undoAction());
    }

    /**
     * 注入完整性守卫（P6 阻塞项 B-4 的回归闸口）。
     *
     * <p><b>键清单来源</b>：UILib 侧 Presentation 类的公共访问器本身 —— P5 规格 §3.2 已把
     * 「Presentation 访问器 = 键」定为命名规范，故这里用反射枚举「返回 {@code String} 且参数全为
     * {@code String}/{@code int}」的公共实例方法（无参键 + 占位符/count 模板键），逐个以探针实参
     * 调用 Miner 注入实例与 UILib 英文默认实例；两者输出相同即说明该键回落英文默认值（用户可见缺陷）。</p>
     *
     * <p><b>为什么不是硬编码清单</b>：硬编码只能证明「我列出的键都在」，无法发现 UILib 后续新增的键；
     * 反射版把 UILib 的公共访问器面当清单，新增键未注入即红（本轮把注入回退到修复前形态实测
     * 14 个键变红：面板 11 + 选择器 3）。{@code int} 参数的 count 重载（如 {@code currentMembersTitle(int)}）
     * 一并覆盖，其文案由同一零参键派生。</p>
     *
     * <p><b>键名对照</b>：信息条单行模板的<b>读取访问器</b>是 {@code infoBarIdLabel(label, id)}
     * （带 {@code {label}}/{@code {id}} 占位符），<b>注入键</b>是 {@code infoBarIdPattern} ——
     * P6 收口报告 §7.1 B-4 列的 13 键漏了它（它是 P5-2 与 {@code hoverHint} 同批新增的面板键），
     * 故本守卫按 UILib 访问器面为准，注入面共 14 键。</p>
     */
    @Test
    public void everyPresentationKeyIsInjectedSoNoChineseUiFallsBackToEnglishDefaults() throws Exception {
        BlockPickerProvider provider = new Fixture().provider();

        List<String> fallbacks = new ArrayList<String>();
        fallbacks.addAll(englishDefaultFallbacks(
                provider.presentation(), SearchPickerPresentation.defaultEnglish()));
        fallbacks.addAll(englishDefaultFallbacks(
                provider.panelPresentation(), SearchPickerPanelPresentation.defaultEnglish()));

        Assert.assertEquals("Presentation 访问器 = 键：以下键仍回落 UILib 英文默认值（中文界面会显示英文）",
                Collections.<String>emptyList(), fallbacks);
    }

    /** @return 「注入值与英文默认值相同」的访问器清单（空 = 全部键都注入了中文文案） */
    private static List<String> englishDefaultFallbacks(Object injected, Object englishDefault)
            throws Exception {
        List<String> fallbacks = new ArrayList<String>();
        Assert.assertNotSame("注入实例不得就是英文默认实例", englishDefault, injected);
        for (Method method : injected.getClass().getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            if (method.getReturnType() != String.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            Object[] probe = new Object[parameters.length];
            boolean addressable = true;
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] == String.class) probe[index] = "探针";
                else if (parameters[index] == int.class) probe[index] = Integer.valueOf(3);
                else { addressable = false; break; }
            }
            if (!addressable) continue;
            String actual = (String) method.invoke(injected, probe);
            String fallback = (String) method.invoke(englishDefault, probe);
            if (actual.equals(fallback)) {
                fallbacks.add(injected.getClass().getSimpleName() + "#" + method.getName()
                        + " 仍为英文默认值：\"" + fallback + "\"");
            }
        }
        return fallbacks;
    }

    private static List<String> categoryKeys(List<SearchPickerCategories.Category> categories) {
        List<String> keys = new ArrayList<String>();
        for (SearchPickerCategories.Category category : categories) keys.add(category.key());
        return keys;
    }

    /** 假清单 + 受控分片物化 + 捕获/物化计数；不读真实注册表、不触碰多方块 API。 */
    private static final class Fixture {
        private final Map<String, BlockCandidate> byRegistry = new LinkedHashMap<String, BlockCandidate>();
        private final CountingSnapshotSource snapshots = new CountingSnapshotSource();
        private final BlockPickerCandidateSource source;
        private int materializations;

        private Fixture(BlockCandidate... candidates) {
            Map<String, Block> blocks = new LinkedHashMap<String, Block>();
            for (BlockCandidate candidate : candidates) {
                byRegistry.put(candidate.registry(), candidate);
                blocks.put(candidate.registry(), new TestBlock());
            }
            snapshots.snapshot = BlockRegistrySnapshot.of(blocks);
            source = new BlockPickerCandidateSource(snapshots, new BlockVariantShardCache(64,
                    new BlockVariantShardCache.Materializer() {
                        @Override
                        public BlockCandidate materialize(String registry, Block block) {
                            materializations++;
                            BlockCandidate candidate = byRegistry.get(registry);
                            return candidate == null ? BlockVariantMaterializer.placeholder(registry) : candidate;
                        }
                    }));
        }

        private BlockPickerProvider provider() {
            return new BlockPickerProvider(source);
        }
    }

    private static final class CountingSnapshotSource implements BlockPickerCandidateSource.SnapshotSource {
        private BlockRegistrySnapshot snapshot;
        private int calls;

        @Override
        public BlockRegistrySnapshot capture() {
            calls++;
            return snapshot;
        }
    }

    /** Block(Material) 是 protected 构造。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
