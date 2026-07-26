package club.heiqi.qz_miner.compat.adapter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.tileentity.TileEntity;

/** IC2/GT CropCard 权威成熟门的反射矩阵。 */
public class Ic2CropCompatAdapterTest {

    /** true/false 必须分别映射 MATURE/IMMATURE。 */
    @Test
    public void cardAuthorityMapsTrueAndFalse() {
        Ic2CropCompatAdapter adapter = adapter();

        Assert.assertEquals(CropGrowthState.MATURE,
                state(adapter, new FakeCropTileEntity(new FakeCropCard(true, false))));
        Assert.assertEquals(CropGrowthState.IMMATURE,
                state(adapter, new FakeCropTileEntity(new FakeCropCard(false, false))));
    }

    /** GT CropCard 子类沿同一 IC2 基类成员路径分类，不需要专用分支。 */
    @Test
    public void cropCardSubclassUsesSameAuthorityMethod() {
        Ic2CropCompatAdapter adapter = adapter();
        Assert.assertEquals(CropGrowthState.MATURE,
                state(adapter, new FakeCropTileEntity(new FakeGtCropCard())));
    }

    /** 空 card、错误 card 类型与调用异常一律 UNKNOWN。 */
    @Test
    public void nullWrongAndThrowingCardsAreUnknown() {
        Ic2CropCompatAdapter adapter = adapter();

        Assert.assertEquals(CropGrowthState.UNKNOWN,
                state(adapter, new FakeCropTileEntity(null)));
        Assert.assertEquals(CropGrowthState.UNKNOWN,
                state(adapter, new FakeCropTileEntity(new Object())));
        Assert.assertEquals(CropGrowthState.UNKNOWN,
                state(adapter, new FakeCropTileEntity(new FakeCropCard(false, true))));
    }

    /** 成员缺失仍保留既有 crop 识别，但成熟度必须 UNKNOWN。 */
    @Test
    public void missingMembersKeepRecognitionButDegradeGrowthState() {
        Ic2CropCompatAdapter adapter = new Ic2CropCompatAdapter(
                MissingCropTileEntity.class, MissingCropTile.class, MissingCropCard.class);
        MissingCropTileEntity tile = new MissingCropTileEntity();

        Assert.assertTrue(adapter.isAvailable());
        Assert.assertTrue(adapter.isCropBlock(null, tile));
        Assert.assertEquals(CropGrowthState.UNKNOWN,
                adapter.growthState(null, 0, 0, 0, null, 0, tile));
    }

    /** 非 IC2 tile 不得被 adapter 分类。 */
    @Test
    public void unrelatedTileIsUnknown() {
        Ic2CropCompatAdapter adapter = adapter();
        Assert.assertFalse(adapter.isCropBlock(null, new TileEntity()));
        Assert.assertEquals(CropGrowthState.UNKNOWN,
                state(adapter, new TileEntity()));
    }

    /** 生产源不得静态链接 IC2，也不得扫描公开成员。 */
    @Test
    public void productionUsesOptionalDeclaredMemberReflectionOnly() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/compat/adapter/Ic2CropCompatAdapter.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertFalse(source.contains("import ic2."));
        Assert.assertFalse(source.contains(".getMethod("));
        Assert.assertTrue(source.contains("ClassNameCompatSupport.resolveClass"));
        Assert.assertTrue(source.contains("ReflectiveMemberSupport.findMethodInHierarchy"));
        Assert.assertTrue(source.contains("canBeHarvested"));
        Assert.assertTrue(source.contains("LinkageError"));
    }

    private static Ic2CropCompatAdapter adapter() {
        return new Ic2CropCompatAdapter(
                FakeCropTileEntity.class, FakeCropTile.class, FakeCropCard.class);
    }

    private static CropGrowthState state(Ic2CropCompatAdapter adapter, TileEntity tile) {
        return adapter.growthState(null, 0, 0, 0, null, 0, tile);
    }

    /** 测试 ICropTile 形状。 */
    private interface FakeCropTile {
        Object getCrop();
    }

    /** 测试 TileEntityCrop。 */
    private static final class FakeCropTileEntity extends TileEntity implements FakeCropTile {
        private final Object cropCard;

        private FakeCropTileEntity(Object cropCard) {
            this.cropCard = cropCard;
        }

        @Override
        public Object getCrop() {
            return cropCard;
        }
    }

    /** 测试 CropCard。 */
    private static class FakeCropCard {
        private final boolean harvestable;
        private final boolean throwing;

        private FakeCropCard(boolean harvestable, boolean throwing) {
            this.harvestable = harvestable;
            this.throwing = throwing;
        }

        public boolean canBeHarvested(FakeCropTile cropTile) {
            if (throwing) {
                throw new IllegalStateException("synthetic crop card failure");
            }
            return harvestable;
        }
    }

    /** 模拟 GT CropCard 子类。 */
    private static final class FakeGtCropCard extends FakeCropCard {
        private FakeGtCropCard() {
            super(true, false);
        }
    }

    /** 缺 getCrop 成员的接口。 */
    private interface MissingCropTile {}

    /** 成员漂移后的 tile。 */
    private static final class MissingCropTileEntity extends TileEntity implements MissingCropTile {}

    /** 缺 canBeHarvested 成员的 card。 */
    private static final class MissingCropCard {}
}
