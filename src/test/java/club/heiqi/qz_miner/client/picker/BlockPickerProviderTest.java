package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.ui.editor.SearchPickerData;
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
    public void currentValuePresenterUsesLocalizedCanonicalAndFallsBackToRaw() {
        BlockPickerProvider provider = new BlockPickerProvider(Collections.singletonList(
                new BlockCandidate("minecraft:stone", "Stone", Collections.<BlockVariant>emptyList(), null)));
        club.heiqi.config.ui.editor.CurrentValuePresenter.Presentation valid =
                provider.currentValuePresenter().present(Collections.singletonList("minecraft:stone@03"));
        Assert.assertEquals("Stone", valid.title());
        Assert.assertEquals("minecraft:stone@3", valid.summary());
        club.heiqi.config.ui.editor.CurrentValuePresenter.Presentation invalid =
                provider.currentValuePresenter().present(Collections.singletonList("not a selector"));
        Assert.assertEquals("not a selector", invalid.title());
        Assert.assertEquals("not a selector", invalid.summary());
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
        Assert.assertEquals("3 个结果", text.resultSummary(3));
        Assert.assertEquals("结果已截断，请继续缩小搜索范围", text.truncated());
        Assert.assertTrue(text.decodeError().contains("原规则未变"));
        Assert.assertTrue(text.searchError().contains("原规则未变"));
        Assert.assertTrue(text.encodeError().contains("原规则未变"));
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
}
