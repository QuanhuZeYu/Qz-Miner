package club.heiqi.qz_miner.chain.planner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 作物/液体交互 matcher 的 live 读取与无世界持有结构合同。 */
public class InteractionMatcherStructureTest {

    private static final String IMMATURE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ImmatureCropBlockMatcher.java";
    private static final String LIQUID_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/LiquidSourceBlockMatcher.java";

    /**
     * 未成熟作物 matcher 每次读取 block/meta/tile，只接受可靠 IMMATURE。
     *
     * <p>「必须 live 读世界」无法在纯 JVM 行为化：本仓测试目录没有 World/EntityPlayer fixture，
     * 且裁定 A 禁止为测试新增可注入 seam/fixture。因此按裁定 11 收敛为<b>方法体区间内</b>的
     * live 读取清单——旧写法在整文件里找 {@code world.getBlock(x, y, z)} 字样，
     * 现在必须先切出 {@code matches} 方法体，把这些读取钉在真正的判定入口上。</p>
     */
    @Test
    public void immatureMatcherReadsAllLiveFactsAndFailsClosed() {
        String matches = matchesBody(IMMATURE_PATH, ImmatureCropBlockMatcher.class);

        JavaSourceSlices.assertContains(matches, "world.getBlock(",
                "matches 必须重新读 live block");
        JavaSourceSlices.assertContains(matches, "world.getBlockMetadata(",
                "matches 必须重新读 live metadata");
        JavaSourceSlices.assertContains(matches, "world.getTileEntity(",
                "matches 必须重新读 live TileEntity");
        JavaSourceSlices.assertContains(matches, "ChainCropRules.isReliablyImmature(",
                "成熟度判定必须复用共享作物规则");
        assertNoLiveStateFields(ImmatureCropBlockMatcher.class);
    }

    /** 液体 matcher 只冻结 fluid identity/source，并在 matches 读取 live block/meta。 */
    @Test
    public void liquidMatcherStoresOnlyFrozenIdentityAndReadsLiveCandidate() {
        String source = JavaSourceSlices.stripped(LIQUID_PATH);

        // 冻结字段清单（反射，与字段名无关）：只允许「纯值身份 + seed 可用性」两类字段。
        Set<Class<?>> fieldTypes = new LinkedHashSet<Class<?>>();
        for (Field field : LiquidSourceBlockMatcher.class.getDeclaredFields()) {
            fieldTypes.add(field.getType());
        }
        Assert.assertEquals("matcher 只允许冻结纯值字段（String 身份 + boolean 可用性）",
                new LinkedHashSet<Class<?>>(Arrays.<Class<?>>asList(String.class, boolean.class)), fieldTypes);

        String matches = matchesBody(LIQUID_PATH, LiquidSourceBlockMatcher.class);
        JavaSourceSlices.assertContains(matches, "world.getBlock(",
                "matches 必须重新读 live block");
        JavaSourceSlices.assertContains(matches, "world.getBlockMetadata(",
                "matches 必须重新读 live metadata");
        JavaSourceSlices.assertContains(matches, "ChainLiquidRules.matchesSource(",
                "候选判定必须委派共享液体规则");

        // 类级边界清单：液体 matcher 不得读 TileEntity、不得排液改世界（去注释后扫描，避免注释/字面量误命中）。
        JavaSourceSlices.assertAbsent(source, "getTileEntity(", "matcher 不得读 TileEntity");
        JavaSourceSlices.assertAbsent(source, ".drain(", "matcher 不得排液改世界");

        assertNoLiveStateFields(LiquidSourceBlockMatcher.class);

        for (Field field : LiquidSourceBlockMatcher.class.getDeclaredFields()) {
            Assert.assertFalse("matcher 不得保留 seed Block", Block.class.isAssignableFrom(field.getType()));
        }
        for (Constructor<?> constructor : LiquidSourceBlockMatcher.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                Assert.assertFalse("matcher 构造器不得接收 live World",
                        World.class.isAssignableFrom(parameterType));
                Assert.assertFalse("matcher 构造器不得接收 live TileEntity",
                        TileEntity.class.isAssignableFrom(parameterType));
            }
        }
    }

    private static String matchesBody(String path, Class<?> matcherType) {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(path),
                "public boolean matches(", matcherType.getSimpleName() + ".matches");
    }

    private static void assertNoLiveStateFields(Class<?> matcherType) {
        for (Field field : matcherType.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            Assert.assertFalse("matcher 不得持有 World", World.class.isAssignableFrom(fieldType));
            Assert.assertFalse("matcher 不得持有 TileEntity", TileEntity.class.isAssignableFrom(fieldType));
        }
    }
}
