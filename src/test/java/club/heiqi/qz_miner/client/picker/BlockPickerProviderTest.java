package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

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
}
