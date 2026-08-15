package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.init.Blocks;

import club.heiqi.config.ui.editor.ListMemberCodec;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.SearchPickerPanelPresentation;
import club.heiqi.config.ui.editor.SearchPickerPresentation;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/** Provider 在构造时固化候选与 SearchFunction。 */
public class BlockPickerProviderTest {
    @Test
    public void providerSearchUsesImmutableSnapshotAndStableVariantKeys() {
        List<BlockCandidate> source = new ArrayList<BlockCandidate>();
        source.add(new BlockCandidate("minecraft:stone", "Stone",
                Collections.singletonList(new BlockVariant(3, "Stone 3", null)), null));
        BlockPickerProvider provider = new BlockPickerProvider(source);
        source.clear();
        Assert.assertEquals("minecraft:stone", provider.searchFunction().search("stone", 64)
                .candidates().get(0).key());
        Assert.assertEquals("minecraft:stone@3", provider.searchFunction().search("stone", 64)
                .candidates().get(0).variants().get(0).key());
    }

    @Test
    public void completeResultOverLimitKeepsExactlyRealEncodableCandidates() {
        List<BlockCandidate> source = new ArrayList<BlockCandidate>();
        Set<String> registries = new HashSet<String>();
        for (int i = 0; i < 65; i++) {
            String registry = "test:block_" + i;
            registries.add(registry);
            source.add(new BlockCandidate(registry, "Matching Block " + i,
                    Collections.<BlockVariant>emptyList(), null));
        }

        SearchPickerData.SearchResult result = new BlockPickerProvider(source).searchFunction().search("matching", 64);

        Assert.assertEquals(65, result.candidates().size());
        Assert.assertFalse(result.truncated());
        for (SearchPickerData.Candidate candidate : result.candidates()) {
            Assert.assertNotEquals("qz_miner:truncated", candidate.key());
            Assert.assertTrue(registries.contains(candidate.key()));
            ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
            Assert.assertEquals(Collections.singletonList(candidate.key() + "@*"), codec.encode(
                    Collections.emptyList(),
                    new SearchPickerData.Selection(candidate.key(), SearchPickerData.SelectionMode.ALL,
                            Collections.<String>emptyList())));
        }
    }

    @Test
    public void currentValuePresenterUsesSafeMalformedCopyAndCanonicalValidValues() {
        BlockPickerProvider provider = new BlockPickerProvider(Collections.singletonList(
                new BlockCandidate("minecraft:stone", "Stone", Collections.<BlockVariant>emptyList(), null)));
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
        SearchPickerPresentation text = new BlockPickerProvider(Collections.<BlockCandidate>emptyList()).presentation();
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
        Assert.assertEquals("编辑", text.edit());
        Assert.assertEquals("删除", text.remove());
        Assert.assertEquals("取消", text.cancelRemove());
        Assert.assertEquals("确认删除", text.confirmRemove());
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
        BlockPickerProvider provider = new BlockPickerProvider(Collections.singletonList(
                new BlockCandidate("minecraft:stone", "Stone", Collections.<BlockVariant>emptyList(), null)));
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
        BlockPickerProvider provider = new BlockPickerProvider(Collections.singletonList(
                new BlockCandidate("minecraft:stone", "Stone", Collections.<BlockVariant>emptyList(), null)));
        Assert.assertTrue(provider.codec() instanceof ListMemberCodec);
        SearchPickerData.Selection knownSelection = new ObjectGroupPickerCodec().decodeMember("minecraft:stone@03");
        SearchPickerData.Candidate knownCandidate = provider.searchFunction().search("stone", 64).candidates().get(0);
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
    public void missingImagesShareScreenPlaceholderButRemainScreenScoped() {
        BlockCandidate missing = new BlockCandidate("test:missing", "Missing",
                Collections.singletonList(new BlockVariant(2, "Missing Variant", null)), null);
        BlockPickerProvider first = new BlockPickerProvider(Collections.singletonList(missing));
        BlockPickerProvider second = new BlockPickerProvider(Collections.singletonList(missing));
        SearchPickerData.Candidate candidate = first.searchFunction().search("missing", 64).candidates().get(0);
        SceneImageSource candidateImage = first.visualAdapter().candidateImage(candidate);

        Assert.assertSame(candidateImage, first.visualAdapter().candidateImage(candidate));
        Assert.assertSame(candidateImage, first.visualAdapter().variantImage(candidate.variants().get(0)));
        Assert.assertNotSame(candidateImage, second.visualAdapter().candidateImage(candidate));
        SearchPickerData.Candidate unknown = new SearchPickerData.Candidate("test:enumeration-fallback", "Fallback",
                Collections.<SearchPickerData.Variant>emptyList());
        Assert.assertSame(candidateImage, first.visualAdapter().candidateImage(unknown));
    }

    @Test
    public void realLitRedstoneOreSearchesByRegistryAndLocalizedNameAndEncodesBothModes() {
        BlockCandidate lit = BlockVariantEnumerator.enumerateBlock(
                "minecraft:lit_redstone_ore", Blocks.lit_redstone_ore);
        BlockPickerProvider provider = new BlockPickerProvider(Collections.singletonList(lit));

        SearchPickerData.Candidate byRegistry = provider.searchFunction()
                .search("lit_redstone_ore", 64).candidates().get(0);
        Assert.assertEquals("minecraft:lit_redstone_ore", byRegistry.key());
        Assert.assertEquals("minecraft:lit_redstone_ore@0", byRegistry.variants().get(0).key());
        Assert.assertEquals(byRegistry.key(), provider.searchFunction()
                .search(lit.localizedName(), 64).candidates().get(0).key());

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
    public void categoriesExposeTwoDimensionsWithStableKeysAndCounts() {
        List<BlockCandidate> source = new ArrayList<BlockCandidate>();
        source.add(new BlockCandidate("minecraft:stone", "minecraft", null, "Stone",
                Collections.<BlockVariant>emptyList(), null));
        source.add(new BlockCandidate("minecraft:dirt", "minecraft", "测试栏", "Dirt",
                Collections.<BlockVariant>emptyList(), null));
        source.add(new BlockCandidate("gt:copper", "gt", "测试栏", "Copper",
                Collections.<BlockVariant>emptyList(), null));
        source.add(new BlockCandidate("galacticraft:venus", "galacticraft", null, "Venus",
                Collections.<BlockVariant>emptyList(), null));
        BlockPickerProvider provider = new BlockPickerProvider(source);
        source.clear();

        Assert.assertEquals(2, provider.categoryDimensionCount());
        List<SearchPickerCategories.Category> dim0 = provider.categories(0);
        List<SearchPickerCategories.Category> dim1 = provider.categories(1);

        SearchPickerCategories.Category other = SearchPickerCategories.find(dim0,
                BlockPickerProvider.OTHER_TAB_KEY);
        Assert.assertNotNull(other);
        Assert.assertEquals(BlockPickerProvider.OTHER_TAB_LABEL, other.label());
        Assert.assertEquals(2, other.count());
        Assert.assertFalse(SearchPickerCategories.contains(dim0, "测试栏"));

        Assert.assertEquals(Arrays.asList("galacticraft", "gt", "minecraft"), categoryKeys(dim1));
        Assert.assertEquals(2, SearchPickerCategories.find(dim1, "minecraft").count());
        Assert.assertEquals(1, SearchPickerCategories.find(dim1, "gt").count());
        Assert.assertEquals(1, SearchPickerCategories.find(dim1, "galacticraft").count());

        try {
            provider.categories(0).add(new SearchPickerCategories.Category("x", "y", 1));
            Assert.fail("categories(0) must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void categoryOfResolvesBothDimensionsAndUnknownKeys() {
        List<BlockCandidate> source = new ArrayList<BlockCandidate>();
        source.add(new BlockCandidate("minecraft:stone", "minecraft", null, "Stone",
                Collections.<BlockVariant>emptyList(), null));
        source.add(new BlockCandidate("minecraft:dirt", "minecraft", "测试栏", "Dirt",
                Collections.<BlockVariant>emptyList(), null));
        BlockPickerProvider provider = new BlockPickerProvider(source);

        Assert.assertEquals(BlockPickerProvider.OTHER_TAB_KEY, provider.categoryOf("minecraft:stone"));
        Assert.assertEquals(BlockPickerProvider.OTHER_TAB_KEY, provider.categoryOf(0, "minecraft:stone"));
        Assert.assertEquals("测试栏", provider.categoryOf(0, "minecraft:dirt"));
        Assert.assertEquals("minecraft", provider.categoryOf(1, "minecraft:stone"));
        Assert.assertEquals("minecraft", provider.categoryOf(1, "minecraft:dirt"));
        Assert.assertNull(provider.categoryOf(1, "missing:block"));
        Assert.assertNull(provider.categoryOf(0, "missing:block"));
        Assert.assertNull(provider.categoryOf(1, null));
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
        SearchPickerPanelPresentation text = new BlockPickerProvider(Collections.<BlockCandidate>emptyList())
                .panelPresentation();
        Assert.assertEquals("选择方块", text.panelTitle());
        Assert.assertEquals(Arrays.asList("创造栏", "按 Mod"), text.categoryDimensions());
        Assert.assertEquals("浏览分类", text.categoryDimensionTitle());
        Assert.assertEquals("全部", text.allCategoryLabel());
        Assert.assertEquals("ID: ", text.tooltipPrefix());
        Assert.assertEquals("无可用分类", text.emptyCategory());
        Assert.assertEquals("选择方块状态", text.variantPanelTitle());
        Assert.assertEquals("筛选状态", text.variantSearchPlaceholder());
        Assert.assertEquals("返回", text.back());
        Assert.assertEquals("关闭", text.close());
        Assert.assertEquals("添加方块", text.addMember());
    }

    private static List<String> categoryKeys(List<SearchPickerCategories.Category> categories) {
        List<String> keys = new ArrayList<String>();
        for (SearchPickerCategories.Category category : categories) keys.add(category.key());
        return keys;
    }
}
