package club.heiqi.qz_miner;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.VersionRange;

/**
 * 联机版本区间的声明契约与边界回归。
 *
 * <p>本仓自 5.4.1 起不再自造版本解析与族判定：区间由构建从制品版本派生
 * （{@link NetworkVersionRange#VALUE}），判定交给 FML 的 Maven ComparableVersion 序语义
 * （{@link VersionRange}）。背景：5.4.0 的兼容族是手写常量、与制品版本脱节，双方在握手阶段
 * 互相拒绝（{@code Rejecting connection CLIENT: [FMLMod:qz_miner{5.4.0}]}），进世界即被踢。</p>
 *
 * <p><b>语义变化（有意）</b>：判定改用 Maven 序后不再做严格语法校验——{@code X.Y} 在 Maven 语义下
 * 等价于 {@code X.Y.0}，因此被接受，而旧实现在此处 fail-closed。取舍理由：解析与比较复用生态
 * 已验证的实现，胜过自造一套并维护它的全部边界。</p>
 */
public class NetworkVersionRangeTest {

    private static final int FAMILY_MAJOR = familyCore(Tags.VERSION)[0];
    private static final int FAMILY_MINOR = familyCore(Tags.VERSION)[1];

    /**
     * 声明区间必须包含自身构建版本。
     *
     * <p>这正是 FML 启动期自检（{@code appears to reject its own version}）的判据，只是提前到构建期；
     * 5.4.0 事故的本质就是这条不变量没有被任何守卫覆盖。</p>
     */
    @Test
    public void declaredRangeMustContainOwnBuildVersion() throws Exception {
        Assert.assertTrue("声明区间必须包含自身构建版本 " + Tags.VERSION + "，实际区间：" + declared(),
                declaredRange().containsVersion(new DefaultArtifactVersion(Tags.VERSION)));
    }

    /**
     * 声明了区间就不得再有 {@code @NetworkCheckHandler}。
     *
     * <p>FML 的 {@code NetworkModHolder} 里两者互斥：存在 handler 时走 {@code MethodNetworkChecker}，
     * {@code acceptableRange} 根本不会被创建——区间与「接受自身版本」自检会一并失效，成为死配置。</p>
     */
    @Test
    public void noNetworkCheckHandlerMayShadowTheDeclaredRange() {
        for (Method method : MyMod.class.getDeclaredMethods()) {
            Assert.assertFalse(
                    method.getName() + " 不得声明 @NetworkCheckHandler：FML 里它会顶掉 acceptableRemoteVersions，"
                            + "使区间（含启动期自检）失效",
                    method.isAnnotationPresent(NetworkCheckHandler.class));
        }
    }

    /** 区间必须恰好覆盖本构建的 major.minor 族（含其预发布与未知限定符），并排除相邻族。 */
    @Test
    public void rangeCoversExactlyTheCurrentMinorFamily() throws Exception {
        VersionRange range = declaredRange();
        String family = FAMILY_MAJOR + "." + FAMILY_MINOR;
        String next = FAMILY_MAJOR + "." + (FAMILY_MINOR + 1);

        String[] inside = {
                family + ".0",
                family + ".0-alpha",
                family + ".0-beta",
                family + ".0-rc.1",
                family + ".0-snapshot",
                family + ".0-0",
                family + ".0-a",
                family + ".0+build.7",
                family + ".1-dev",
                family + ".2147483647"
        };
        for (String version : inside) {
            Assert.assertTrue("同族必须放行：" + version,
                    range.containsVersion(new DefaultArtifactVersion(version)));
        }

        int previousMinor = FAMILY_MINOR == 0 ? 1 : FAMILY_MINOR - 1;
        String[] outside = {
                next + ".0-alpha",
                next + ".0",
                next + ".0-a",
                next + ".1",
                FAMILY_MAJOR + "." + previousMinor + ".9",
                (FAMILY_MAJOR + 1) + "." + FAMILY_MINOR + ".0"
        };
        for (String version : outside) {
            Assert.assertFalse("跨族必须拒绝：" + version,
                    range.containsVersion(new DefaultArtifactVersion(version)));
        }
    }

    /** Maven 语义下 {@code X.Y} 与 {@code X.Y.0} 等价——本仓改用 FML 区间后接受的宽容形态。 */
    @Test
    public void mavenSemanticsAcceptShortFormAsEquivalent() throws Exception {
        String shortForm = FAMILY_MAJOR + "." + FAMILY_MINOR;
        Assert.assertTrue("Maven 语义下 " + shortForm + " 等价于 " + shortForm + ".0",
                declaredRange().containsVersion(new DefaultArtifactVersion(shortForm)));
    }

    private static VersionRange declaredRange() throws Exception {
        return VersionRange.createFromVersionSpec(declared());
    }

    private static String declared() {
        Mod declaration = MyMod.class.getAnnotation(Mod.class);
        Assert.assertNotNull("MyMod 必须保留 @Mod 声明", declaration);
        String range = declaration.acceptableRemoteVersions();
        Assert.assertFalse("必须是构建期生成的非空区间，不能是注解默认空串", range.isEmpty());
        Assert.assertEquals("声明值必须来自构建生成的常量", NetworkVersionRange.VALUE, range);
        return range;
    }

    /** 取制品版本的 {@code {major, minor}}；非法形态在这里就是构建问题，直接失败。 */
    private static int[] familyCore(String version) {
        String[] parts = version.split("[.+-]", 3);
        if (parts.length < 2) {
            throw new IllegalStateException("制品版本不是 major.minor.patch 形态：" + version);
        }
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }
}
