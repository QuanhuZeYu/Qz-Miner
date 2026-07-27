package club.heiqi.qz_miner.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 玩家 endpoint replace/remove 前必须完成 local restore 屏障。 */
public class PlayerManagerToolSwapLifecycleStructureTest {

    @Test
    public void logoutRespawnDimensionAndCloneFinalizeBeforeEndpointMutation() throws Exception {
        String source = source();
        assertBefore(source, "private void onPlayerDisconnectOnServerThread",
                "finalizeAutoToolSwap(uuid, player, null, CloseCause.LOGOUT)", "players.remove(uuid)");
        assertBefore(source, "public void onPlayerRespawn",
                "finalizeAutoToolSwap(uuid, previous, player, CloseCause.RESPAWN)", "players.put(uuid, player)");
        assertBefore(source, "public void onPlayerChangedDimension",
                "finalizeAutoToolSwap(uuid, previous, player, CloseCause.DIMENSION_CHANGE)",
                "players.put(uuid, player)");
        assertBefore(source, "public void onPlayerClone",
                "finalizeAutoToolSwap(uuid, oldPlayer, newPlayer, CloseCause.CLONE)",
                "players.put(uuid, newPlayer)");
    }

    @Test
    public void cloneSuppliesBothOldAndNewEndpointsAndServerStopFinalizesEachPlayer() throws Exception {
        String source = source();
        Assert.assertTrue(source.contains("event.original == null ? players.get(uuid) : event.original"));
        int loop = source.indexOf("for (Map.Entry<UUID, EntityPlayer> entry");
        int finalize = source.indexOf("CloseCause.SERVER_STOP", loop);
        int clear = source.indexOf("instance.players.clear()", loop);
        Assert.assertTrue(loop >= 0 && finalize > loop && clear > finalize);
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
