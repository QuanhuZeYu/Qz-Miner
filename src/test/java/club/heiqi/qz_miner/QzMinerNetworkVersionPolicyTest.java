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

/** 5.3 family 握手语法、矩阵与 Forge 接线回归。 */
public class QzMinerNetworkVersionPolicyTest {

    private static final String[] LEGAL_VERSIONS = {
            "5.3.0",
            "5.3.2147483647",
            "5.3.1-alpha",
            "5.3.2-alpha.1",
            "5.3.3-RC-1+build.0007",
            "5.3.0-ci+abcdef0123456789",
            "5.3.9-soft-deadline.17+abcdef12",
            "5.3.12-branch-name+abcdef12-dirty"
    };

    /** FML 的 {@code @NetworkCheckHandler} 注解描述符（RUNTIME 保留，写进注解表）。 */
    private static final String NETWORK_CHECK_HANDLER = "Lcpw/mods/fml/common/network/NetworkCheckHandler;";

    @Test
    public void legalStablePrereleaseBranchAndDirtyVersionsAreAccepted() {
        for (String version : LEGAL_VERSIONS) {
            Assert.assertTrue(version, QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.3.0-0"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.3.0+0001"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.3.0-a-b.C-D+0.00-x"));
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
        String[] rejected = {
                "",
                "5.0.24",
                "5.1.1",
                "5.2.1",
                "5.10.0",
                "4.3.0",
                "5.3",
                "5.3.0.1",
                "05.3.0",
                "5.03.0",
                "5.3.00",
                "2147483648.3.0",
                "5.2147483648.0",
                "5.3.2147483648",
                "5.3.-1",
                "5.3.0-",
                "5.3.0+",
                "5.3.0-alpha.",
                "5.3.0-alpha..1",
                "5.3.0-01",
                "5.3.0-alpha.01",
                "5.3.0+build.",
                "5.3.0+build..1",
                "5.3.0-alpha_beta",
                "5.3.0+build_beta",
                "5.3.0-alpha+build+extra",
                "5.3.0-α",
                "5.3.0+构建",
                "x5.3.0",
                "5.3.0x",
                " 5.3.0",
                "5.3.0 ",
                "5.3.0\n"
        };
        Assert.assertFalse(QzMinerNetworkVersionPolicy.isCompatibleFamily(null));
        for (String version : rejected) {
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

        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.3.0", null, MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.3.0", Collections.<String, String>emptyMap(), null, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.3.0", Collections.<String, String>emptyMap(), MyMod.MODID, null));

        Map<String, String> nullVersion = singletonVersion(null);
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts("5.3.0", nullVersion, MyMod.MODID, Side.SERVER));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.3.0", singletonVersion(""), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                null, singletonVersion("5.3.0"), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.2.1", singletonVersion("5.3.0"), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.3.0", singletonVersion("5.2.1"), MyMod.MODID, Side.SERVER));
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
     *       （非空会绕过整个版本策略放行任意远端版本）。</li>
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
            assertHandlerOnlyDelegatesToPolicy(mod, singletonVersion("5.3.0"), side);
            assertHandlerOnlyDelegatesToPolicy(mod, singletonVersion("5.2.1"), side);
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

    private static Map<String, String> singletonVersion(String version) {
        Map<String, String> versions = new HashMap<String, String>();
        versions.put(MyMod.MODID, version);
        return versions;
    }
}
