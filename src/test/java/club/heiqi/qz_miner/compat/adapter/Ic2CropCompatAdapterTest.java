package club.heiqi.qz_miner.compat.adapter;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.tileentity.TileEntity;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** IC2/GT CropCard 权威成熟门的反射矩阵。 */
public class Ic2CropCompatAdapterTest {

    private static final String ADAPTER_INTERNAL_NAME =
            "club/heiqi/qz_miner/compat/adapter/Ic2CropCompatAdapter";

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

    /** 适配器编译产物不得静态链接 IC2，也不得扫描公开成员（断言对象＝常量池，不是源码文本）。 */
    @Test
    public void productionUsesOptionalDeclaredMemberReflectionOnly() throws Exception {
        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(ADAPTER_INTERNAL_NAME));
        Assert.assertFalse("不得静态链接 IC2（常量池不得出现 ic2/ 类型）: " + refs.classRefs,
                refs.hasClassRefUnder("ic2/"));
        Assert.assertFalse("成员查找不得裸调 Class.getMethod",
                refs.methodRefs.contains("java/lang/Class#getMethod"));
        Assert.assertTrue("可选类型解析必须经 ClassNameCompatSupport.resolveClass",
                refs.methodRefs.contains("club/heiqi/qz_miner/compat/adapter/ClassNameCompatSupport#resolveClass"));
        Assert.assertTrue("成员查找必须经 ReflectiveMemberSupport.findMethodInHierarchy",
                refs.methodRefs.contains(
                        "club/heiqi/qz_miner/compat/adapter/ReflectiveMemberSupport#findMethodInHierarchy"));
        // 已删除的两条源码文本断言与其实测证据：
        // ① contains("canBeHarvested")：成员名已被本文件 fake CropCard 矩阵行为覆盖——生产里把名字写错，
        //    findMethodInHierarchy 返回 null，上面三态用例直接变 UNKNOWN 而红；文本命中还会被注释/日志的同名串误报。
        // ② contains("LinkageError")：实测 Method.invoke 会把被调方法抛出的 NoClassDefFoundError 包成
        //    InvocationTargetException（cause=NoClassDefFoundError），而本适配器对成员缺失与调用失败一律
        //    返回 UNKNOWN，外部无法区分「吞了 LinkageError」与「吞了其它异常」，该断言只能守拼写、守不到行为。
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
