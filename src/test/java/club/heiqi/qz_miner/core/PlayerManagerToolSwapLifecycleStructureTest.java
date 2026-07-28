package club.heiqi.qz_miner.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 玩家 endpoint replace/remove 前必须完成 local restore 屏障。 */
public class PlayerManagerToolSwapLifecycleStructureTest {

    @Test
    public void vanillaPreHooksFinalizeBeforeEndpointMutation() throws Exception {
        String source = source();
        Assert.assertTrue(source.contains("beforeVanillaRespawn(EntityPlayerMP player)"));
        Assert.assertTrue(source.contains("finalizeTrackedEndpoint(player, null, CloseCause.RESPAWN)"));
        Assert.assertTrue(source.contains("beforeVanillaDimensionChange(EntityPlayerMP player)"));
        Assert.assertTrue(source.contains("finalizeTrackedEndpoint(player, null, CloseCause.DIMENSION_CHANGE)"));
        Assert.assertTrue(source.contains("instance.players.replace(uuid, previous, player)"));
    }

    @Test
    public void staleDisconnectCannotRemoveCurrentEndpointAndServerStopFinalizesEachPlayer() throws Exception {
        String source = source();
        assertBefore(source, "public static void onVanillaDisconnect",
                "CloseCause.LOGOUT", "instance.players.remove(uuid, player)");
        assertBefore(source, "public static void onVanillaLoginCommitted",
                "new PlayerStateEvent(previous, Reason.LOGOUT)", "instance.players.put(uuid, player)");
        int loop = source.indexOf("for (Map.Entry<UUID, EntityPlayer> entry");
        int finalize = source.indexOf("CloseCause.SERVER_STOP", loop);
        int clear = source.indexOf("instance.players.clear()", loop);
        Assert.assertTrue(loop >= 0 && finalize > loop && clear > finalize);
    }

    @Test
    public void playerLifecycleDoesNotRegisterForgeOrFmlEvents() throws Exception {
        String source = source();
        Assert.assertFalse(source.contains("@SubscribeEvent"));
        Assert.assertFalse(source.contains("MinecraftForge.EVENT_BUS"));
        Assert.assertFalse(source.contains("FMLCommonHandler.instance().bus()"));
        Assert.assertFalse(source.contains("PlayerLoggedInEvent"));
        Assert.assertFalse(source.contains("PlayerRespawnEvent"));
        Assert.assertFalse(source.contains("PlayerChangedDimensionEvent"));
    }

    private static void assertBefore(String source, String method, String barrier, String mutation) {
        int start = source.indexOf(method);
        int barrierAt = source.indexOf(barrier, start);
        int mutationAt = source.indexOf(mutation, start);
        Assert.assertTrue(method, start >= 0 && barrierAt > start && mutationAt > barrierAt);
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/core/PlayerManager.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
