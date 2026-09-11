package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import club.heiqi.config.ui.editor.PickerIconSource;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/**
 * 图标源契约测试：候选域 key 入口、快照语义、无 Miner 侧缓存、占位下沉 UILib，
 * 以及 U-A9（候选域键需 UILib seam）的现状刻画。
 */
public class BlockPickerIconSourceTest {

    @Test
    public void candidateAndVariantIconsSnapshotTheStackAtRequestTime() {
        ItemStack representative = new ItemStack(new Item(), 1, 1);
        ItemStack variantStack = new ItemStack(new Item(), 1, 3);
        BlockPickerIconSource icons = icons(new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", variantStack)), representative));

        HostImageSource candidateImage = (HostImageSource) icons.candidateIcon("test:block");
        HostImageSource variantImage = (HostImageSource) icons.variantIcon("test:block", "3");

        representative.setItemDamage(9);
        variantStack.setItemDamage(10);

        Assert.assertEquals(HostImageSource.Kind.ITEM_ICON, candidateImage.getKind());
        Assert.assertEquals("请求时快照（HostImageSource 工厂内 copy）", 1,
                candidateImage.getItemIconStack().getItemDamage());
        Assert.assertEquals(HostImageSource.Kind.ITEM_ICON, variantImage.getKind());
        Assert.assertEquals(3, variantImage.getItemIconStack().getItemDamage());
        Assert.assertNotSame("无 Miner 侧缓存：每次请求新建（缓存归 UILib PickerIconCache）",
                candidateImage, icons.candidateIcon("test:block"));
    }

    @Test
    public void variantIconAcceptsMetaTokenAndFullVariantKey() {
        ItemStack variantStack = new ItemStack(new Item(), 1, 3);
        BlockPickerIconSource icons = icons(new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", variantStack)), null));

        Assert.assertEquals(3, ((HostImageSource) icons.variantIcon("test:block", "3"))
                .getItemIconStack().getItemDamage());
        Assert.assertEquals(3, ((HostImageSource) icons.variantIcon("test:block", "test:block@3"))
                .getItemIconStack().getItemDamage());
        Assert.assertNull(icons.variantIcon("test:block", "not-a-meta"));
        Assert.assertNull(icons.variantIcon("test:block", "7"));
        Assert.assertNull(icons.variantIcon("test:absent", "3"));
    }

    @Test
    public void nullVariantSpecFallsBackToCandidateIconAndMissingStacksReturnNull() {
        BlockCandidate block = new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", null)),
                new ItemStack(new Item(), 1, 0));
        BlockPickerIconSource icons = icons(block);

        SceneImageSource candidate = icons.candidateIcon("test:block");
        Assert.assertNotNull(candidate);
        SceneImageSource wholeVariant = icons.variantIcon("test:block", null);
        Assert.assertNotNull("null 变体 = 候选整体（同一解析路径，非同一实例：无缓存）", wholeVariant);
        Assert.assertEquals(HostImageSource.Kind.ITEM_ICON, ((HostImageSource) wholeVariant).getKind());
        Assert.assertEquals(0, ((HostImageSource) wholeVariant).getItemIconStack().getItemDamage());
        Assert.assertNull("变体无栈 → 无图（占位归 UILib）", icons.variantIcon("test:block", "3"));
        Assert.assertNull(icons.candidateIcon("test:absent"));
        Assert.assertNull(icons.candidateIcon(null));

        BlockCandidate noStack = new BlockCandidate("test:nostack", "NoStack",
                Collections.singletonList(new BlockVariant(0, "NoStack", null)), null);
        Assert.assertNull(icons(noStack).candidateIcon("test:nostack"));
    }

    @Test
    public void visualAdapterRoutesVariantKeysBackToCandidateContext() {
        ItemStack variantStack = new ItemStack(new Item(), 1, 3);
        BlockPickerIconSource icons = icons(new BlockCandidate("test:block", "Block",
                Collections.singletonList(new BlockVariant(3, "Variant", variantStack)), null));
        BlockPickerVisualAdapter adapter = new BlockPickerVisualAdapter(icons);
        SearchPickerData.Variant variant = new SearchPickerData.Variant("test:block@3", "Variant (3)");

        Assert.assertEquals(3, ((HostImageSource) adapter.variantImage(variant))
                .getItemIconStack().getItemDamage());
        Assert.assertEquals("Variant (3)", adapter.variantLabel(variant));
        Assert.assertNull("候选级键（无 meta）不产生变体图标",
                adapter.variantImage(new SearchPickerData.Variant("test:block", "Block")));
        Assert.assertNull(adapter.variantImage(null));
        Assert.assertNull(adapter.candidateImage(null));
    }

    /**
     * U-A9 现状刻画：图标源必须返回可渲染的 {@link HostImageSource}（渲染唯一入口
     * {@code UiRenderContext.drawImage} 只认它），而该类型是 final 且 {@code registryKey()} 自算，
     * 因此候选域键需要 UILib 侧显式键工厂；本用例把「必须可渲染」钉死，翻面时只改断言。
     */
    @Test
    public void returnedSourceIsRenderableHostImageSoCandidateKeyNeedsUilibSeam() {
        BlockCandidate block = new BlockCandidate("minecraft:stone", "Stone",
                Collections.singletonList(new BlockVariant(0, "Stone", new ItemStack(new Item(), 1, 0))),
                new ItemStack(new Item(), 1, 0));
        SceneImageSource source = icons(block).candidateIcon("minecraft:stone");

        Assert.assertNotNull(source);
        Assert.assertTrue("必须可渲染（UiRenderContext.drawImage 只认 instanceof HostImageSource）",
                source instanceof HostImageSource);
        // U-A9：HostImageSource 是 final 且 registryKey() 由内部 ItemStack 自算（Item 注册名:meta）。
        // 合成 Item 未注册 ⇒ headless 下键为 null；真机上形如 "minecraft:stone:0"（Item 域）。
        // 候选域键（PickerIconKey.candidate/variant）需要 UILib 侧显式键工厂，届时本断言改为等值断言。
        Assert.assertNull("现有 API 无法自报候选域键", source.registryKey());
    }

    private static BlockPickerIconSource icons(BlockCandidate candidate) {
        final Map<String, BlockCandidate> byRegistry = new LinkedHashMap<String, BlockCandidate>();
        byRegistry.put(candidate.registry(), candidate);
        Map<String, Block> blocks = new LinkedHashMap<String, Block>();
        blocks.put(candidate.registry(), new TestBlock());
        BlockPickerCandidateSource source = new BlockPickerCandidateSource(
                new BlockPickerCandidateSource.SnapshotSource() {
                    @Override
                    public BlockRegistrySnapshot capture() {
                        return BlockRegistrySnapshot.of(blocks());
                    }

                    private Map<String, Block> blocks() {
                        return blocks;
                    }
                },
                new BlockVariantShardCache(8, new BlockVariantShardCache.Materializer() {
                    @Override
                    public BlockCandidate materialize(String registry, Block block) {
                        BlockCandidate hit = byRegistry.get(registry);
                        return hit == null ? BlockVariantMaterializer.placeholder(registry) : hit;
                    }
                }));
        return new BlockPickerIconSource(source);
    }

    /** Block(Material) 是 protected 构造。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
