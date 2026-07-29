package club.heiqi.qz_miner.mixins;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** vanilla 玩家生命周期注入点的结构门禁。 */
public class ServerConfigurationManagerLifecycleMixinStructureTest {

    @Test
    public void loginRespawnAndDimensionUseCanonicalVanillaBoundaries() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/mixins/early/MixinServerConfigurationManager.java").toPath()),
                StandardCharsets.UTF_8);

        Assert.assertTrue(source.contains("@Mixin(ServerConfigurationManager.class)"));
        Assert.assertTrue(source.contains("initializeConnectionToPlayer(Lnet/minecraft/network/NetworkManager;"));
        Assert.assertTrue(source.contains("func_72355_a(Lnet/minecraft/network/NetworkManager;"));
        Assert.assertTrue(source.contains("PlayerManager.onVanillaLoginCommitted(player)"));
        Assert.assertTrue(source.contains(
                "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZ)Lnet/minecraft/entity/player/EntityPlayerMP;"));
        Assert.assertTrue(source.contains("PlayerManager.beforeVanillaRespawn(player)"));
        Assert.assertTrue(source.contains("PlayerManager.onVanillaRespawnCommitted(player, cir.getReturnValue())"));
        Assert.assertTrue(source.contains(
                "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V"));
        Assert.assertTrue(source.contains(
                "func_72356_a(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V"));
        Assert.assertTrue(source.contains("PlayerManager.beforeVanillaDimensionChange(player)"));
        Assert.assertTrue(source.contains("PlayerManager.onVanillaDimensionChangeCommitted(player)"));
        Assert.assertFalse("two-argument delegate must not be injected",
                source.contains("EntityPlayerMP;I)V"));
    }

    @Test
    public void earlyLoaderAndConfigBothRegisterLifecycleMixin() throws Exception {
        String loader = read("src/main/java/club/heiqi/qz_miner/mixins/QzMinerEarlyMixinLoader.java");
        String config = read("src/main/resources/mixins.qz_miner.early.json");
        Assert.assertTrue(loader.contains("\"MixinServerConfigurationManager\""));
        Assert.assertTrue(config.contains("\"MixinServerConfigurationManager\""));
    }

    @Test
    public void disconnectMixinAlsoRemapsVanillaMethodNames() throws Exception {
        String source = read(
                "src/main/java/club/heiqi/qz_miner/mixins/early/MixinNetHandlerPlayServer.java");
        Assert.assertTrue(source.contains("@Mixin(NetHandlerPlayServer.class)"));
        Assert.assertFalse(source.contains("remap = false"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
