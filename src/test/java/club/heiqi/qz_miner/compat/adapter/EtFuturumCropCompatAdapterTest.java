package club.heiqi.qz_miner.compat.adapter;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** Et Futurum Requiem 作物 metadata 成熟矩阵。 */
public class EtFuturumCropCompatAdapterTest {

    private final EtFuturumCropCompatAdapter adapter = new EtFuturumCropCompatAdapter(
            FakeBerryBush.class, FakeCaveVines.class);

    /** berry 0/1 未成熟，2/3 成熟，其它 metadata 未知。 */
    @Test
    public void berryMetadataMatrix() {
        Block berry = new FakeBerryBush();

        assertState(CropGrowthState.IMMATURE, berry, 0);
        assertState(CropGrowthState.IMMATURE, berry, 1);
        assertState(CropGrowthState.MATURE, berry, 2);
        assertState(CropGrowthState.MATURE, berry, 3);
        assertState(CropGrowthState.UNKNOWN, berry, -1);
        assertState(CropGrowthState.UNKNOWN, berry, 4);
    }

    /** cave vines 0 未成熟，1 成熟，其它 metadata 未知。 */
    @Test
    public void caveVinesMetadataMatrix() {
        Block vines = new FakeCaveVines();

        assertState(CropGrowthState.IMMATURE, vines, 0);
        assertState(CropGrowthState.MATURE, vines, 1);
        assertState(CropGrowthState.UNKNOWN, vines, -1);
        assertState(CropGrowthState.UNKNOWN, vines, 2);
    }

    /** 既有 isCropBlock 行为保留，普通方块不扩张为 EFR 作物。 */
    @Test
    public void cropRecognitionIsPreservedAndNarrow() {
        Block berry = new FakeBerryBush();
        Block vines = new FakeCaveVines();
        Block unrelated = new UnrelatedBlock();

        Assert.assertTrue(adapter.isAvailable());
        Assert.assertTrue(adapter.isCropBlock(berry, null));
        Assert.assertTrue(adapter.isCropBlock(vines, null));
        Assert.assertFalse(adapter.isCropBlock(unrelated, null));
        assertState(CropGrowthState.UNKNOWN, unrelated, 0);
    }

    private void assertState(CropGrowthState expected, Block block, int metadata) {
        Assert.assertEquals(expected,
                adapter.growthState(null, 0, 0, 0, block, metadata, null));
    }

    /** 测试 berry block。 */
    private static final class FakeBerryBush extends Block {
        private FakeBerryBush() {
            super(Material.plants);
        }
    }

    /** 测试 cave vines block。 */
    private static final class FakeCaveVines extends Block {
        private FakeCaveVines() {
            super(Material.vine);
        }
    }

    /** 非 EFR 方块。 */
    private static final class UnrelatedBlock extends Block {
        private UnrelatedBlock() {
            super(Material.rock);
        }
    }
}
