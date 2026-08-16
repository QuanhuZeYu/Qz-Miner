package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.Assert;
import org.junit.Test;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.uilib.ui.image.HostImageSource;

/** Picker 图标懒建缓存 + 首次请求快照策略的测试。 */
public class BlockPickerVisualAdapterTest {
    @Test
    public void candidateAndVariantLazyBuildAndCacheSnapshot() {
        ItemStack representative = new ItemStack(new Item(), 1, 1);
        ItemStack variantStack = new ItemStack(new Item(), 1, 3);
        BlockCandidate block = new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", variantStack)), representative);
        Map<String, BlockCandidate> index = new HashMap<String, BlockCandidate>();
        index.put("test:block", block);
        BlockPickerVisualAdapter adapter = new BlockPickerVisualAdapter(index);
        SearchPickerData.Variant variant = new SearchPickerData.Variant("test:block@3", "Variant");
        SearchPickerData.Candidate candidate = new SearchPickerData.Candidate("test:block", "Block",
                Collections.singletonList(variant));

        // 首次请求懒建并缓存快照
        HostImageSource candidateImage = (HostImageSource) adapter.candidateImage(candidate);
        HostImageSource variantImage = (HostImageSource) adapter.variantImage(variant);
        // 源栈后续改动不影响已缓存快照
        representative.setItemDamage(9);
        variantStack.setItemDamage(10);
        Assert.assertEquals(HostImageSource.Kind.ITEM_ICON, candidateImage.getKind());
        Assert.assertEquals(1, candidateImage.getItemIconStack().getItemDamage());
        Assert.assertEquals(HostImageSource.Kind.ITEM_ICON, variantImage.getKind());
        Assert.assertEquals(3, variantImage.getItemIconStack().getItemDamage());
        // 二次请求命中缓存（同一实例，不重建）
        Assert.assertSame(candidateImage, adapter.candidateImage(candidate));
        Assert.assertSame(variantImage, adapter.variantImage(variant));
    }
}
