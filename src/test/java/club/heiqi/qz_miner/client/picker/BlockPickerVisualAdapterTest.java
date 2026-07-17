package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.Assert;
import org.junit.Test;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.uilib.ui.image.HostImageSource;

/** Picker 图标采用 screen 生命周期静态快照的测试。 */
public class BlockPickerVisualAdapterTest {
    @Test
    public void candidateAndVariantUseSnapshotPolicy() {
        ItemStack representative = new ItemStack(new Item(), 1, 1);
        ItemStack variantStack = new ItemStack(new Item(), 1, 3);
        BlockCandidate block = new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", variantStack)), representative);
        BlockPickerVisualAdapter adapter = new BlockPickerVisualAdapter(Collections.singletonList(block));
        SearchPickerData.Variant variant = new SearchPickerData.Variant("test:block@3", "Variant");
        SearchPickerData.Candidate candidate = new SearchPickerData.Candidate("test:block", "Block",
                Collections.singletonList(variant));
        HostImageSource candidateImage = (HostImageSource) adapter.candidateImage(candidate);
        HostImageSource variantImage = (HostImageSource) adapter.variantImage(variant);
        representative.setItemDamage(9);
        variantStack.setItemDamage(10);
        Assert.assertEquals(HostImageSource.ItemPolicy.SNAPSHOT, candidateImage.getItemPolicy());
        Assert.assertEquals(1, candidateImage.getItemStack().getItemDamage());
        Assert.assertEquals(HostImageSource.ItemPolicy.SNAPSHOT, variantImage.getItemPolicy());
        Assert.assertEquals(3, variantImage.getItemStack().getItemDamage());
    }
}
