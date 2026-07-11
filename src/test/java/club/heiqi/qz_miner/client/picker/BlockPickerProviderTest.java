package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.ui.editor.SearchPickerData;
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

        Assert.assertEquals(64, result.candidates().size());
        Assert.assertTrue(result.truncated());
        for (SearchPickerData.Candidate candidate : result.candidates()) {
            Assert.assertNotEquals("qz_miner:truncated", candidate.key());
            Assert.assertTrue(registries.contains(candidate.key()));
            ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
            Assert.assertEquals(Collections.singletonList(candidate.key() + "@*"), codec.encode(
                    new SearchPickerData.Selection(candidate.key(), SearchPickerData.SelectionMode.ALL,
                            Collections.<String>emptyList())));
        }
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
