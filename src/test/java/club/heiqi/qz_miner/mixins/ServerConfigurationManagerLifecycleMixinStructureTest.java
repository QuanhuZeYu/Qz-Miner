package club.heiqi.qz_miner.mixins;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.Inject;

import com.google.gson.JsonObject;

import club.heiqi.qz_miner.mixins.early.MixinNetHandlerPlayServer;
import club.heiqi.qz_miner.mixins.early.MixinServerConfigurationManager;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.qz_miner.testsupport.JsonResources;

/**
 * vanilla 玩家生命周期注入点的结构门禁。
 *
 * <p>{@code @Mixin} 是 CLASS 保留（反射不可见），目标类只能从编译产物的类字面量确认；
 * {@code @Inject} 是 RUNTIME 保留，注入点选择器与 remap/at 都能从产物精确读出；
 * 「哪个 handler 委托哪个 PlayerManager 入口」用 handler 方法体切片 + 实参数据流判定。</p>
 */
public class ServerConfigurationManagerLifecycleMixinStructureTest {

    private static final String SCM_SOURCE =
            "src/main/java/club/heiqi/qz_miner/mixins/early/MixinServerConfigurationManager.java";
    private static final String SCM_TYPE =
            "club/heiqi/qz_miner/mixins/early/MixinServerConfigurationManager";
    private static final String EARLY_CONFIG = "src/main/resources/mixins.qz_miner.early.json";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN_NAME = "MixinServerConfigurationManager";

    @Test
    public void loginRespawnAndDimensionUseCanonicalVanillaBoundaries() throws Exception {
        Assert.assertTrue("mixin 必须指向 ServerConfigurationManager",
                CompiledClasses.references(MixinServerConfigurationManager.class,
                        "Lnet/minecraft/server/management/ServerConfigurationManager;"));
        for (String selector : new String[] {
                "initializeConnectionToPlayer(Lnet/minecraft/network/NetworkManager;"
                        + "Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V",
                "func_72355_a(Lnet/minecraft/network/NetworkManager;"
                        + "Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V",
                "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZ)"
                        + "Lnet/minecraft/entity/player/EntityPlayerMP;",
                "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;I"
                        + "Lnet/minecraft/world/Teleporter;)V",
                "func_72356_a(Lnet/minecraft/entity/player/EntityPlayerMP;I"
                        + "Lnet/minecraft/world/Teleporter;)V" }) {
            Assert.assertTrue("注入点选择器必须精确出现在编译产物：" + selector,
                    CompiledClasses.references(MixinServerConfigurationManager.class, selector));
        }
        Assert.assertEquals("恰好五个生命周期注入 handler（把成对选择器拆成两个 @Inject 会让 MCP/SRG 名字对失效）",
                5, injectHandlerNames().size());
        Assert.assertFalse("不得注入两参 delegate",
                CompiledClasses.references(MixinServerConfigurationManager.class, "EntityPlayerMP;I)V"));

        String masked = JavaSourceSlices.maskedMainSource(SCM_SOURCE);
        assertHandlerDelegates(masked, "onLoginCommitted", "PlayerManager.onVanillaLoginCommitted");
        assertHandlerDelegates(masked, "beforeRespawn", "PlayerManager.beforeVanillaRespawn");
        assertHandlerDelegates(masked, "beforeDimensionChange", "PlayerManager.beforeVanillaDimensionChange");
        assertHandlerDelegates(masked, "onDimensionChangeCommitted",
                "PlayerManager.onVanillaDimensionChangeCommitted");

        String respawnCommitted = JavaSourceSlices.methodBodyWithoutSignature(masked, "onRespawnCommitted");
        JavaSourceSlices.assertContains(respawnCommitted, "PlayerManager.onVanillaRespawnCommitted",
                "重生 RETURN 注入必须回调 PlayerManager");
        JavaSourceSlices.assertContains(
                JavaSourceSlices.callArguments(respawnCommitted, "PlayerManager.onVanillaRespawnCommitted",
                        "重生提交回调"),
                "getReturnValue()",
                "重生提交必须传递原版返回的真实端点，而不是新造一个");
    }

    @Test
    public void earlyLoaderAndConfigBothRegisterLifecycleMixin() throws Exception {
        List<String> loaded = new QzMinerEarlyMixinLoader().getMixins(Collections.<String>emptySet());
        Assert.assertTrue("early loader 必须注册 " + MIXIN_NAME + ": " + loaded,
                loaded.contains(MIXIN_NAME));

        JsonObject config = JsonResources.readRepoFile(EARLY_CONFIG);
        Assert.assertTrue("early mixin 清单必须包含 mixins 数组", JsonResources.hasArray(config, "mixins"));
        Set<String> declared = JsonResources.arrayEntrySet(config, "mixins");
        Assert.assertTrue("early mixin 配置必须声明 " + MIXIN_NAME + ": " + declared,
                declared.contains(MIXIN_NAME));
    }

    @Test
    public void disconnectMixinAlsoRemapsVanillaMethodNames() throws Exception {
        Assert.assertTrue("mixin 必须指向 NetHandlerPlayServer",
                CompiledClasses.references(MixinNetHandlerPlayServer.class,
                        "Lnet/minecraft/network/NetHandlerPlayServer;"));

        int injections = 0;
        for (Method method : MixinNetHandlerPlayServer.class.getDeclaredMethods()) {
            Inject inject = method.getAnnotation(Inject.class);
            if (inject == null) {
                continue;
            }
            injections++;
            Assert.assertTrue("原版目标必须走 refmap 重映射（remap 显式关闭后 SRG selector 不会被映射）: "
                    + method.getName(), inject.remap());
        }
        Assert.assertEquals("断开注入点必须存在且唯一", 1, injections);
    }

    private static Set<String> injectHandlerNames() throws Exception {
        Set<String> handlers = new LinkedHashSet<String>();
        for (CompiledClasses.MethodInfo method : CompiledClasses.methods(
                CompiledClasses.forInternalName(SCM_TYPE))) {
            if (method.annotations.contains(INJECT_ANNOTATION)) {
                handlers.add(method.name);
            }
        }
        return handlers;
    }

    private static void assertHandlerDelegates(String masked, String handlerName, String delegation) {
        JavaSourceSlices.assertContains(
                JavaSourceSlices.methodBodyWithoutSignature(masked, handlerName),
                delegation, handlerName + " 必须委托 " + delegation);
    }
}
