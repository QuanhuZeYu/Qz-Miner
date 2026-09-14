package club.heiqi.qz_miner.mixins;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;

import club.heiqi.qz_miner.mixins.client.MixinPlayerControllerMPToolSwap;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.qz_miner.testsupport.JsonResources;

/**
 * 自动工具只保留首块成功 Mixin，网络事务路径必须为零。
 *
 * <p>断言对象是编译产物的注解元数据与 mixin 清单数据：{@code @Inject} 是 RUNTIME 保留，
 * 注入点可从产物读出；回调顺序与「只走 true 返回值分支」用 handler 方法体切片判定；
 * 注册清单按 JSON 解析后判归属，不再整文件 {@code contains}。</p>
 */
public class AutoToolSwapMixinStructureTest {

    private static final String CONTROLLER_SOURCE =
            "src/main/java/club/heiqi/qz_miner/mixins/client/MixinPlayerControllerMPToolSwap.java";
    private static final String CONTROLLER_TYPE =
            "club/heiqi/qz_miner/mixins/client/MixinPlayerControllerMPToolSwap";
    private static final String NETWORK_MIXIN_SOURCE =
            "src/main/java/club/heiqi/qz_miner/mixins/client/MixinNetHandlerPlayClientToolSwap.java";
    private static final String MIXIN_CONFIG = "src/main/resources/mixins.qz_miner.json";
    private static final String CLIENT_MIXIN = "client.MixinPlayerControllerMPToolSwap";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    /** 注入点必须唯一且精确命中原版「本地破坏成功」的完整描述符；回调只走 true 返回值分支且预览先于换位。 */
    @Test
    public void destroyHookRemainsButNetworkMixinIsRemoved() throws Exception {
        int injections = 0;
        for (CompiledClasses.MethodInfo method : CompiledClasses.methods(
                CompiledClasses.forInternalName(CONTROLLER_TYPE))) {
            if (method.annotations.contains(INJECT_ANNOTATION)) {
                injections++;
            }
        }
        Assert.assertEquals("只允许一个注入点（最小侵入）", 1, injections);
        Assert.assertTrue("注入点必须精确命中原版破坏成功方法的完整描述符",
                CompiledClasses.references(MixinPlayerControllerMPToolSwap.class, "onPlayerDestroyBlock(IIII)Z"));

        String handler = JavaSourceSlices.methodBodyWithoutSignature(
                JavaSourceSlices.maskedMainSource(CONTROLLER_SOURCE), "qzMiner$afterPlayerDestroyBlock");
        int guardAt = handler.indexOf("if (");
        Assert.assertTrue("handler 必须按原版返回值分支，不得无条件触发破坏回调", guardAt >= 0);
        int guardBrace = handler.indexOf('{', guardAt);
        Assert.assertTrue("返回值分支必须带花括号体", guardBrace > guardAt);
        String guardCondition = handler.substring(guardAt, guardBrace);
        Assert.assertTrue("分支条件必须读取原版返回值（getReturnValueZ()/getReturnValue() 皆可）: " + guardCondition,
                guardCondition.contains("getReturnValue"));
        String guarded = JavaSourceSlices.blockAfter(handler, "if (");
        JavaSourceSlices.assertAbsent(handler.substring(0, guardAt), "onLocalBlockDestroyed",
                "进入返回值分支之前不得触发任何破坏回调");
        JavaSourceSlices.assertBefore(guarded, "chainPreviewController.onLocalBlockDestroyed(",
                "AutoToolSwapHooks.onLocalBlockDestroyed()", "预览回调必须先于工具换位回调");

        Assert.assertFalse("网络 mixin 必须已删除", new File(NETWORK_MIXIN_SOURCE).exists());
    }

    /** mixin 归属表契约：该 mixin 必须注册在 client 数组，且已删除的网络 mixin 不得在任何数组里复活。 */
    @Test
    public void clientMixinListDoesNotRegisterVanillaNetworkPath() throws Exception {
        JsonObject config = JsonResources.readRepoFile(MIXIN_CONFIG);
        Set<String> clientEntries = JsonResources.arrayEntrySet(config, "client");

        Assert.assertTrue("client mixin 必须注册在 client 数组: " + clientEntries,
                clientEntries.contains(CLIENT_MIXIN));
        Assert.assertFalse("客户端 mixin 不得注册进通用 mixins 数组（会在服务端侧加载）",
                JsonResources.arrayEntrySet(config, "mixins").contains(CLIENT_MIXIN));
        Assert.assertFalse("客户端 mixin 不得注册进 server 数组",
                JsonResources.arrayEntrySet(config, "server").contains(CLIENT_MIXIN));

        Set<String> registered = new LinkedHashSet<String>();
        registered.addAll(JsonResources.arrayEntrySet(config, "mixins"));
        registered.addAll(clientEntries);
        registered.addAll(JsonResources.arrayEntrySet(config, "server"));
        for (String entry : registered) {
            Assert.assertFalse("已删除的网络 mixin 不得重新注册: " + entry,
                    entry.contains("MixinNetHandlerPlayClientToolSwap"));
        }
    }
}
