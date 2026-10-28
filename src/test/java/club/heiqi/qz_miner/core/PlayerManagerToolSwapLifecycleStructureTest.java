package club.heiqi.qz_miner.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 验证玩家 endpoint 改动前的恢复委派顺序，以及 PlayerManager 的事件总线依赖边界。
 * 方法体切片检查调用顺序，编译产物检查类型及注解；实际库存恢复和世界时序另经实机确认。
 */
public class PlayerManagerToolSwapLifecycleStructureTest {

    private static final String SOURCE_PATH =
            "src/main/java/club/heiqi/qz_miner/core/PlayerManager.java";
    private static final String CLASS_NAME = "club/heiqi/qz_miner/core/PlayerManager";

    /** 用于核对 Mixin 侧事件归属的类型清单，覆盖 FML 嵌套命名。 */
    private static final String[] FORBIDDEN_EVENT_TYPES = {
            "PlayerLoggedInEvent",
            "PlayerRespawnEvent",
            "PlayerChangedDimensionEvent"
    };

    @Test
    public void vanillaPreHooksFinalizeBeforeEndpointMutation() throws Exception {
        String source = JavaSourceSlices.read(SOURCE_PATH);

        String respawnHook = JavaSourceSlices.methodBody(source,
                "public static void beforeVanillaRespawn(EntityPlayerMP player)",
                "PlayerManager.beforeVanillaRespawn");
        JavaSourceSlices.assertContains(respawnHook, "finalizeTrackedEndpoint(",
                "respawn 前必须走统一 finalize 屏障");
        JavaSourceSlices.assertContains(respawnHook, "CloseCause.RESPAWN",
                "respawn 屏障必须以 RESPAWN 原因收口");

        String dimensionHook = JavaSourceSlices.methodBody(source,
                "public static void beforeVanillaDimensionChange(EntityPlayerMP player)",
                "PlayerManager.beforeVanillaDimensionChange");
        JavaSourceSlices.assertContains(dimensionHook, "finalizeTrackedEndpoint(",
                "切维度前必须走统一 finalize 屏障");
        JavaSourceSlices.assertContains(dimensionHook, "CloseCause.DIMENSION_CHANGE",
                "切维度屏障必须以 DIMENSION_CHANGE 原因收口");

        String committed = JavaSourceSlices.methodBody(source,
                "public static void onVanillaRespawnCommitted(EntityPlayerMP previous, EntityPlayerMP player)",
                "PlayerManager.onVanillaRespawnCommitted");
        int mutation = JavaSourceSlices.requireAt(committed, "respawn 提交必须原子替换 endpoint",
                "players.replace(");
        String replaced = JavaSourceSlices.assignmentTarget(committed, mutation,
                "替换结果必须被捕获");
        JavaSourceSlices.assertContains(committed, "if (!" + replaced + ")",
                "替换失败必须提前返回（不得在别人已经换过 endpoint 之后再发布 RESPAWN）");
    }

    @Test
    public void staleDisconnectCannotRemoveCurrentEndpointAndServerStopFinalizesEachPlayer() throws Exception {
        String source = JavaSourceSlices.read(SOURCE_PATH);

        String disconnect = JavaSourceSlices.methodBody(source,
                "public static void onVanillaDisconnect(EntityPlayerMP player, IChatComponent reason)",
                "PlayerManager.onVanillaDisconnect");
        JavaSourceSlices.assertBefore(disconnect, "CloseCause.LOGOUT", "players.remove(",
                "断线必须先 finalize 再摘 endpoint");
        List<String> removed = JavaSourceSlices.splitCallArguments(disconnect, "players.remove");
        Assert.assertEquals("摘除必须是身份条件式（key, value），按键裸删会误删新连接: " + removed,
                2, removed.size());

        String login = JavaSourceSlices.methodBody(source,
                "public static void onVanillaLoginCommitted(EntityPlayerMP player)",
                "PlayerManager.onVanillaLoginCommitted");
        JavaSourceSlices.assertBefore(login, "finalizeAutoToolSwap(", "Reason.LOGOUT",
                "接管的旧 endpoint 必须先 finalize 再发布 LOGOUT");
        JavaSourceSlices.assertBefore(login, "Reason.LOGOUT", "players.put(",
                "旧 endpoint 的 LOGOUT 必须早于映射表覆盖");

        String clear = JavaSourceSlices.methodBody(source, "private static void clearAllPlayersOnServerThread()",
                "PlayerManager.clearAllPlayersOnServerThread");
        String loop = JavaSourceSlices.blockAfter(clear, "for (");
        JavaSourceSlices.assertContains(loop, "CloseCause.SERVER_STOP",
                "停机必须逐个玩家以 SERVER_STOP 收口");
        JavaSourceSlices.assertBefore(clear, "CloseCause.SERVER_STOP", "players.clear()",
                "必须先逐个 finalize 再清空玩家表");
    }

    @Test
    public void playerLifecycleDoesNotRegisterForgeOrFmlEvents() throws Exception {
        File classFile = CompiledClasses.forInternalName(CLASS_NAME);

        List<String> annotated = new ArrayList<String>();
        for (CompiledClasses.MethodInfo method : CompiledClasses.methods(classFile)) {
            if (method.annotations.contains(CompiledClasses.SUBSCRIBE_EVENT)) {
                annotated.add(method.toString());
            }
        }
        Assert.assertTrue("PlayerManager 不得订阅事件总线（生命周期由 Mixin 调用）: " + annotated,
                annotated.isEmpty());

        CompiledClasses.Refs refs = CompiledClasses.refs(classFile);
        Assert.assertFalse("不得访问 MinecraftForge.EVENT_BUS",
                refs.fieldRefs.contains("net/minecraftforge/common/MinecraftForge#EVENT_BUS"));
        Assert.assertFalse("不得通过 MinecraftForge 注册（换写法也算）",
                refs.hasClassRefUnder("net/minecraftforge/common/MinecraftForge"));
        Assert.assertFalse("不得获取 FML 事件总线",
                refs.methodRefs.contains("cpw/mods/fml/common/FMLCommonHandler#instance"));

        for (String forbidden : FORBIDDEN_EVENT_TYPES) {
            Assert.assertFalse("不得依赖玩家生命周期事件类: " + forbidden,
                    hasClassRefEndingWith(refs.classRefs, forbidden));
        }
    }

    private static boolean hasClassRefEndingWith(java.util.Set<String> classRefs, String simpleName) {
        for (String reference : classRefs) {
            if (reference.endsWith(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
