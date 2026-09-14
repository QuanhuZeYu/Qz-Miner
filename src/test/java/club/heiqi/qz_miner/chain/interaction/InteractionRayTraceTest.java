package club.heiqi.qz_miner.chain.interaction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * 共享右键射线的 common-only 边界、Item 姿态数值语义与液体标志透传合同。
 *
 * <p>数值部分是真的行为断言：姿态/视线数学已下沉为包级纯函数（消除「必须先造出 EntityPlayer」
 * 的隐藏依赖），因此可以直接按数值证伪——改错插值公式、yaw/pitch 约定、reach 方向、眼高分支
 * 都会红。真实玩家与世界的部分（1.7.10 玩家实体构造要求 WorldServer）不可达，只保留
 * 「液体标志必须原样透传给世界查询」这一条结构契约。</p>
 */
public class InteractionRayTraceTest {

    private static final String SOURCE =
            "src/main/java/club/heiqi/qz_miner/chain/interaction/InteractionRayTrace.java";

    /** MathHelper 查表量化会让「应为 0」的方向分量留下约 1e-4 残差（数值经 Python 验算）。 */
    private static final double LOOK_TOLERANCE = 1.0E-3D;

    /** 纯工具类：final、不可实例化、入口签名冻结（反射读真实修饰符与签名）。 */
    @Test
    public void helperIsFinalUninstantiableAndKeepsFrozenSignature() throws Exception {
        Assert.assertTrue("射线工具类必须 final",
                Modifier.isFinal(InteractionRayTrace.class.getModifiers()));

        Constructor<InteractionRayTrace> constructor =
                InteractionRayTrace.class.getDeclaredConstructor();
        Assert.assertTrue("射线工具类不得被实例化", Modifier.isPrivate(constructor.getModifiers()));
        Assert.assertEquals(0, constructor.getParameterTypes().length);

        Method trace = InteractionRayTrace.class.getDeclaredMethod(
                "trace", EntityPlayer.class, double.class, boolean.class);
        Assert.assertTrue("trace 必须是静态入口", Modifier.isStatic(trace.getModifiers()));
        Assert.assertTrue("trace 必须是公开入口", Modifier.isPublic(trace.getModifiers()));
        Assert.assertEquals(MovingObjectPosition.class, trace.getReturnType());
    }

    /** common-only 边界：编译产物不得引用客户端类与客户端专有依赖，也不得持有游戏对象。 */
    @Test
    public void helperStaysSideNeutralAndRetainsNoGameObjects() {
        Assert.assertFalse("不得引用客户端类型",
                CompiledClasses.references(InteractionRayTrace.class, "net/minecraft/client/"));
        Assert.assertFalse("不得引用 @SideOnly 客户端专有依赖",
                CompiledClasses.references(InteractionRayTrace.class, "cpw/mods/fml/relauncher/"));
        Assert.assertFalse("不得耦合客户端单例",
                CompiledClasses.references(InteractionRayTrace.class, "getMinecraft"));

        for (Field field : InteractionRayTrace.class.getDeclaredFields()) {
            Assert.assertFalse("不得持有实体引用: " + field.getName(),
                    Entity.class.isAssignableFrom(field.getType()));
            Assert.assertFalse("不得持有世界引用: " + field.getName(),
                    World.class.isAssignableFrom(field.getType()));
        }
    }

    /** Item 右键语义固定取当前帧：partialTicks=1 时插值结果就是当前值。 */
    @Test
    public void poseInterpolationFollowsItemPoseSemantics() {
        Assert.assertEquals("partialTicks=1 必须取当前角度",
                30.0F, InteractionRayTrace.interpolateAngle(0.0F, 30.0F, 1.0F), 0.0F);
        Assert.assertEquals("半帧插值必须线性",
                15.0F, InteractionRayTrace.interpolateAngle(0.0F, 30.0F, 0.5F), 0.0F);
        Assert.assertEquals("partialTicks=1 必须取当前坐标",
                64.0D, InteractionRayTrace.interpolatePosition(0.0D, 64.0D, 1.0F), 0.0D);
        Assert.assertEquals("半帧插值必须线性",
                32.0D, InteractionRayTrace.interpolatePosition(0.0D, 64.0D, 0.5F), 0.0D);
    }

    /** 眼高偏移只在 remote 侧扣掉默认眼高（视角跟随手持物），服务端取完整眼高。 */
    @Test
    public void eyeHeightOffsetOnlyTrimsDefaultHeightOnRemoteSide() {
        Assert.assertEquals("remote 侧必须扣掉默认眼高",
                0.18D, InteractionRayTrace.eyeHeightOffset(true, 1.8D, 1.62D), 1.0E-9D);
        Assert.assertEquals("服务端必须取完整眼高",
                1.8D, InteractionRayTrace.eyeHeightOffset(false, 1.8D, 1.62D), 0.0D);
    }

    /** yaw/pitch → 视线向量必须符合 Minecraft 约定：yaw=0 朝 +Z、yaw=90 朝 -X、pitch=90 朝下。 */
    @Test
    public void lookVectorFollowsMinecraftYawPitchConvention() {
        Vec3 forward = InteractionRayTrace.lookVector(0.0F, 0.0F);
        Assert.assertEquals(0.0D, forward.xCoord, LOOK_TOLERANCE);
        Assert.assertEquals(0.0D, forward.yCoord, 0.0D);
        Assert.assertEquals(1.0D, forward.zCoord, 0.0D);

        Vec3 west = InteractionRayTrace.lookVector(90.0F, 0.0F);
        Assert.assertEquals(-1.0D, west.xCoord, 0.0D);
        Assert.assertEquals(0.0D, west.zCoord, LOOK_TOLERANCE);

        Vec3 down = InteractionRayTrace.lookVector(0.0F, 90.0F);
        Assert.assertEquals(-1.0D, down.yCoord, 0.0D);
        Assert.assertEquals(0.0D, down.xCoord, LOOK_TOLERANCE);
        Assert.assertEquals(0.0D, down.zCoord, LOOK_TOLERANCE);
    }

    /** 终点 = 眼位 + 视线 × reach。 */
    @Test
    public void endPointExtendsEyeAlongLookVector() {
        Vec3 end = InteractionRayTrace.endPoint(
                Vec3.createVectorHelper(1.0D, 2.0D, 3.0D), 0.0F, 0.0F, 5.0D);

        Assert.assertEquals(1.0D, end.xCoord, LOOK_TOLERANCE);
        Assert.assertEquals(2.0D, end.yCoord, 0.0D);
        Assert.assertEquals(8.0D, end.zCoord, 0.0D);
    }

    /** 玩家或世界缺失必须返回 null，不得抛异常（纯 JVM 可达路径）。 */
    @Test
    public void traceFailsClosedWithoutPlayerOrWorld() {
        Assert.assertNull(InteractionRayTrace.trace(null, 5.0D, true));
    }

    /** 世界查询必须原样透传调用方的 includeLiquids，不得写死成 true/false。 */
    @Test
    public void traceForwardsCallerLiquidFlagToWorldQuery() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String body = JavaSourceSlices.methodBodyWithoutSignature(code, "trace");
        List<String> arguments = JavaSourceSlices.splitCallArguments(body, "func_147447_a");

        Assert.assertEquals("原版射线查询的实参表固定", 5, arguments.size());
        Assert.assertTrue("起点必须是插值眼位: " + arguments.get(0), arguments.get(0).contains("eye"));
        Assert.assertTrue("终点必须由视线与 reach 推出: " + arguments.get(1),
                arguments.get(1).contains("end"));
        Assert.assertEquals("第三个实参必须是调用方传入的 includeLiquids（不得写死）",
                "includeLiquids", arguments.get(2));
        Assert.assertEquals("原版语义开关必须保持 false", "false", arguments.get(3));
        Assert.assertEquals("原版语义开关必须保持 false", "false", arguments.get(4));
    }
}
