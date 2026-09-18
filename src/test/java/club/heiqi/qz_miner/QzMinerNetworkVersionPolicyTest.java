package club.heiqi.qz_miner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.relauncher.Side;

/**
 * 联机版本族策略的语法、矩阵与 Forge 接线回归。
 *
 * <p>样本全部由制品版本的族派生（见 {@link #FAMILY_PREFIX}），不写死版本数字。族曾写死为
 * 5.3 而制品走到 5.4.0：双方握手时互相拒绝（{@code Rejecting connection CLIENT:
 * [FMLMod:qz_miner{5.4.0}]}），而当时的样本恰好全是 5.3.x，绕过了「自身构建版本必须被策略
 * 接受」这条不变量——本类现在显式钉住它（{@link #policyAcceptsItsOwnBuildVersion()}）。</p>
 */
public class QzMinerNetworkVersionPolicyTest {

    /** 本构建版本的族前缀（{@code major.minor}），如 {@code 5.4}。 */
    private static final String FAMILY_PREFIX = familyCore(Tags.VERSION)[0] + "." + familyCore(Tags.VERSION)[1];
    private static final int FAMILY_MAJOR = familyCore(Tags.VERSION)[0];
    private static final int FAMILY_MINOR = familyCore(Tags.VERSION)[1];

    /** 同族的稳定版、预发布版与 branch/dirty 版样本。 */
    private static final String[] LEGAL_VERSIONS = {
            FAMILY_PREFIX + ".0",
            FAMILY_PREFIX + ".2147483647",
            FAMILY_PREFIX + ".1-alpha",
            FAMILY_PREFIX + ".2-alpha.1",
            FAMILY_PREFIX + ".3-RC-1+build.0007",
            FAMILY_PREFIX + ".0-ci+abcdef0123456789",
            FAMILY_PREFIX + ".9-soft-deadline.17+abcdef12",
            FAMILY_PREFIX + ".12-branch-name+abcdef12-dirty"
    };

    /** FML 的 {@code @NetworkCheckHandler} 注解描述符（RUNTIME 保留，写进注解表）。 */
    private static final String NETWORK_CHECK_HANDLER = "Lcpw/mods/fml/common/network/NetworkCheckHandler;";

    /**
     * 策略必须接受自身构建版本——5.4.0 事故的直接守卫。
     *
     * <p>覆盖三层：族判定、策略判定、以及真的 new 出模组实例走 Forge 握手入口。任何一层与制品
     * 版本脱节都会红，不必等真机联机才暴露。</p>
     */
    @Test
    public void policyAcceptsItsOwnBuildVersion() {
        Assert.assertTrue("自身构建版本必须属于兼容族：" + Tags.VERSION,
                QzMinerNetworkVersionPolicy.isCompatibleFamily(Tags.VERSION));

        for (Side side : new Side[] {Side.CLIENT, Side.SERVER}) {
            Assert.assertTrue("双方都跑同一构建版本时必须放行：" + Tags.VERSION + " on " + side,
                    QzMinerNetworkVersionPolicy.accepts(
                            Tags.VERSION, singletonVersion(Tags.VERSION), MyMod.MODID, side));
        }

        MyMod mod = new MyMod();
        for (Side side : new Side[] {Side.CLIENT, Side.SERVER}) {
            Assert.assertTrue("Forge 握手入口必须放行同版本的自己：" + Tags.VERSION + " on " + side,
                    mod.checkNetworkVersions(singletonVersion(Tags.VERSION), side));
        }
    }

    @Test
    public void legalStablePrereleaseBranchAndDirtyVersionsAreAccepted() {
        for (String version : LEGAL_VERSIONS) {
            Assert.assertTrue(version, QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily(FAMILY_PREFIX + ".0-0"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily(FAMILY_PREFIX + ".0+0001"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily(FAMILY_PREFIX + ".0-a-b.C-D+0.00-x"));
    }

    @Test
    public void everyLegalPatchAndQualifierCombinationInteroperatesOnBothSides() {
        for (String local : LEGAL_VERSIONS) {
            for (String remote : LEGAL_VERSIONS) {
                Map<String, String> versions = singletonVersion(remote);
                Assert.assertTrue(local + " -> " + remote + " CLIENT",
                        QzMinerNetworkVersionPolicy.accepts(local, versions, MyMod.MODID, Side.CLIENT));
                Assert.assertTrue(local + " -> " + remote + " SERVER",
                        QzMinerNetworkVersionPolicy.accepts(local, versions, MyMod.MODID, Side.SERVER));
            }
        }
    }

    @Test
    public void otherFamiliesAndMalformedVersionsAreRejectedWithoutNormalization() {
        Assert.assertFalse(QzMinerNetworkVersionPolicy.isCompatibleFamily(null));

        for (String version : otherFamilyVersions()) {
            Assert.assertFalse("跨族必须拒绝: " + version,
                    QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }

        String[] malformed = {
                "",
                FAMILY_PREFIX,
                FAMILY_PREFIX + ".0.1",
                "0" + FAMILY_PREFIX + ".0",
                FAMILY_MAJOR + ".0" + FAMILY_MINOR + ".0",
                FAMILY_PREFIX + ".00",
                "2147483648." + FAMILY_MINOR + ".0",
                FAMILY_MAJOR + ".2147483648.0",
                FAMILY_PREFIX + ".2147483648",
                FAMILY_PREFIX + ".-1",
                FAMILY_PREFIX + ".0-",
                FAMILY_PREFIX + ".0+",
                FAMILY_PREFIX + ".0-alpha.",
                FAMILY_PREFIX + ".0-alpha..1",
                FAMILY_PREFIX + ".0-01",
                FAMILY_PREFIX + ".0-alpha.01",
                FAMILY_PREFIX + ".0+build.",
                FAMILY_PREFIX + ".0+build..1",
                FAMILY_PREFIX + ".0-alpha_beta",
                FAMILY_PREFIX + ".0+build_beta",
                FAMILY_PREFIX + ".0-alpha+build+extra",
                FAMILY_PREFIX + ".0-α",
                FAMILY_PREFIX + ".0+构建",
                "x" + FAMILY_PREFIX + ".0",
                FAMILY_PREFIX + ".0x",
                " " + FAMILY_PREFIX + ".0",
                FAMILY_PREFIX + ".0 ",
                FAMILY_PREFIX + ".0\n"
        };
        for (String version : malformed) {
            Assert.assertFalse(version, QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }
    }

    @Test
    public void missingRemoteModIsAllowedButNullEnvelopeAndPresentBadValuesFailClosed() {
        for (Side side : new Side[] {Side.CLIENT, Side.SERVER}) {
            Assert.assertTrue(QzMinerNetworkVersionPolicy.accepts(
                    null, Collections.<String, String>emptyMap(), MyMod.MODID, side));
            Assert.assertTrue(QzMinerNetworkVersionPolicy.accepts(
                    "not-a-version", Collections.singletonMap("other_mod", "1.0.0"), MyMod.MODID, side));
        }

        String otherFamily = otherFamilyVersions()[0];
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                Tags.VERSION, null, MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                Tags.VERSION, Collections.<String, String>emptyMap(), null, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                Tags.VERSION, Collections.<String, String>emptyMap(), MyMod.MODID, null));

        Map<String, String> nullVersion = singletonVersion(null);
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(Tags.VERSION, nullVersion, MyMod.MODID, Side.SERVER));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                Tags.VERSION, singletonVersion(""), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                null, singletonVersion(Tags.VERSION), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                otherFamily, singletonVersion(Tags.VERSION), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                Tags.VERSION, singletonVersion(otherFamily), MyMod.MODID, Side.SERVER));
    }

    /**
     * Forge 版本握手入口的唯一性与「只委派策略」合同。
     *
     * <p>旧写法读全部 {@code src/main} 文本做字符统计与归一化整句匹配：换行、提局部变量、
     * 加一行日志都会误报，而「处理器偷偷改成恒 true」这种真回归照样绿。现在分三层：</p>
     * <ol>
     *   <li><b>唯一性</b>：扫编译产物的方法注解表（{@code @NetworkCheckHandler} 是 RUNTIME 注解），
     *       全仓只允许一个，且签名冻结为 {@code (Map, Side)}；</li>
     *   <li><b>行为</b>：真的 new 出模组实例调用该入口，逐例与
     *       {@link QzMinerNetworkVersionPolicy#accepts} 的返回值比对——改成恒 true / 换常量都会红；</li>
     *   <li><b>旁路禁令</b>：{@code @Mod.acceptableRemoteVersions} 必须保持空串
     *       （非空会绕过整个版本策略放行任意远端版本）。注意该属性的另一面：FML 的
     *       「accepts its own version」自检只读它，不读本策略，故那条日志不构成放行证据。</li>
     * </ol>
     */
    @Test
    public void myModHasOneUnparameterizedHandlerThatOnlyDelegatesToPolicy() throws Exception {
        List<CompiledClasses.MethodInfo> handlers = new ArrayList<CompiledClasses.MethodInfo>();
        List<String> owners = new ArrayList<String>();
        for (File classFile : CompiledClasses.classFiles()) {
            if (!CompiledClasses.relative(classFile).startsWith("club/heiqi/qz_miner/")) {
                continue;
            }
            for (CompiledClasses.MethodInfo method : CompiledClasses.methods(classFile)) {
                if (method.annotations.contains(NETWORK_CHECK_HANDLER)) {
                    handlers.add(method);
                    owners.add(CompiledClasses.relative(classFile) + "#" + method);
                }
            }
        }
        Assert.assertEquals("全仓只允许唯一 @NetworkCheckHandler: " + owners, 1, handlers.size());
        CompiledClasses.MethodInfo handler = handlers.get(0);
        Assert.assertEquals("唯一入口必须是 checkNetworkVersions", "checkNetworkVersions", handler.name);
        Assert.assertEquals("握手入口签名冻结: " + handler.descriptor,
                Arrays.asList("java/util/Map", "cpw/mods/fml/relauncher/Side"),
                handler.parameterInternalNames());

        MyMod mod = new MyMod();
        for (Side side : new Side[] {Side.CLIENT, Side.SERVER}) {
            assertHandlerOnlyDelegatesToPolicy(mod, singletonVersion(Tags.VERSION), side);
            assertHandlerOnlyDelegatesToPolicy(mod, singletonVersion(otherFamilyVersions()[0]), side);
            assertHandlerOnlyDelegatesToPolicy(mod, Collections.<String, String>emptyMap(), side);
        }

        Mod annotation = MyMod.class.getAnnotation(Mod.class);
        Assert.assertNotNull("@Mod 注解必须存在", annotation);
        Assert.assertEquals("不得用 acceptableRemoteVersions 放行任意远端版本",
                "", annotation.acceptableRemoteVersions());
    }

    private static void assertHandlerOnlyDelegatesToPolicy(MyMod mod, Map<String, String> remote, Side side) {
        Assert.assertEquals("握手入口只允许委派版本策略: " + remote + " on " + side,
                QzMinerNetworkVersionPolicy.accepts(Tags.VERSION, remote, MyMod.MODID, side),
                mod.checkNetworkVersions(remote, side));
    }

    /**
     * 语法合法但 {@code major.minor} 与本构建族不同的样本。
     *
     * <p>含两条「防前缀匹配」样本（{@code 5}→{@code 50}、minor 追加 {@code 0}）：实现若退化成
     * 字符串前缀比较，这两条会误放行。</p>
     */
    private static String[] otherFamilyVersions() {
        return new String[] {
                (FAMILY_MAJOR + 1) + "." + FAMILY_MINOR + ".0",
                FAMILY_MAJOR + "." + (FAMILY_MINOR + 1) + ".0",
                FAMILY_MAJOR + "." + (FAMILY_MINOR + 1) + ".7+build.1",
                FAMILY_MAJOR + "0." + FAMILY_MINOR + ".0",
                FAMILY_MAJOR + "." + FAMILY_MINOR + "0.0",
                (FAMILY_MAJOR == 0 ? 1 : FAMILY_MAJOR - 1) + "." + FAMILY_MINOR + ".0"
        };
    }

    /** 取制品版本的 {@code {major, minor}}；非法形态在这里就是构建问题，直接失败。 */
    private static int[] familyCore(String version) {
        String[] parts = version.split("[.+-]", 3);
        if (parts.length < 2) {
            throw new IllegalStateException("制品版本不是 major.minor.patch 形态：" + version);
        }
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static Map<String, String> singletonVersion(String version) {
        Map<String, String> versions = new HashMap<String, String>();
        versions.put(MyMod.MODID, version);
        return versions;
    }
}
