package club.heiqi.qz_miner.chain.executor;

import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainPlayerDropBuffer;
import club.heiqi.qz_miner.chain.state.ChainPlayerDropBuffer.FallbackTarget;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * 玩家级掉落缓冲释放助手。
 */
public final class ChainDropReleaseHelper {

    private ChainDropReleaseHelper() {}

    /**
     * 尝试将缓冲掉落释放到玩家当前所在位置。
     *
     * @param player 玩家
     * @param buffer 掉落缓冲
     * @param reason 释放原因
     * @return 是否已成功释放
     */
    public static boolean releaseAtPlayer(EntityPlayerMP player, ChainPlayerDropBuffer buffer, String reason) {
        if (player == null || buffer == null || buffer.isEmpty()) {
            return false;
        }

        return releaseAtCoordinates(player.worldObj, player.posX, player.posY, player.posZ, buffer, player.getUniqueID().toString(), reason);
    }

    /**
     * 使用玩家实例解析重生点或世界出生点，尝试释放缓冲掉落。
     *
     * @param player 玩家实例
     * @param buffer 掉落缓冲
     * @param reason 释放原因
     * @return 是否已成功释放
     */
    public static boolean releaseAtRespawnOrWorldSpawn(EntityPlayer player, ChainPlayerDropBuffer buffer, String reason) {
        if (player == null || buffer == null || buffer.isEmpty()) {
            return false;
        }

        WorldServer world = resolveRespawnWorld(player);
        if (world == null) {
            world = resolveFallbackWorld(player);
        }
        if (world == null) {
            return false;
        }

        ChunkCoordinates spawn = resolveRespawnCoordinates(player, world);
        if (spawn == null) {
            spawn = world.getSpawnPoint();
        }
        if (spawn == null) {
            return false;
        }

        double spawnX = (double) spawn.posX + 0.5D;
        double spawnY = (double) spawn.posY + 0.1D;
        double spawnZ = (double) spawn.posZ + 0.5D;
        buffer.rememberFallbackTarget(world.provider.dimensionId, spawnX, spawnY, spawnZ);
        return releaseAtCoordinates(world, spawnX, spawnY, spawnZ, buffer, player.getUniqueID().toString(), reason + "-respawn-fallback");
    }

    /**
     * 记录当前玩家可用的重生点或世界出生点，供后续释放使用。
     *
     * @param player 玩家
     * @param buffer 掉落缓冲
     */
    public static void rememberRespawnOrWorldSpawn(EntityPlayer player, ChainPlayerDropBuffer buffer) {
        if (player == null || buffer == null) {
            return;
        }

        WorldServer world = resolveRespawnWorld(player);
        if (world == null) {
            world = resolveFallbackWorld(player);
        }
        if (world == null) {
            return;
        }

        ChunkCoordinates spawn = resolveRespawnCoordinates(player, world);
        if (spawn == null) {
            spawn = world.getSpawnPoint();
        }
        if (spawn == null) {
            return;
        }

        buffer.rememberFallbackTarget(
            world.provider.dimensionId,
            (double) spawn.posX + 0.5D,
            (double) spawn.posY + 0.1D,
            (double) spawn.posZ + 0.5D);
    }

    /**
     * 使用已缓存的兜底释放坐标释放掉落。
     *
     * @param buffer 掉落缓冲
     * @param playerIdentity 玩家标识
     * @param reason 释放原因
     * @return 是否已成功释放
     */
    public static boolean releaseAtRememberedTarget(ChainPlayerDropBuffer buffer, String playerIdentity, String reason) {
        if (buffer == null || buffer.isEmpty()) {
            return false;
        }

        FallbackTarget target = buffer.getFallbackTarget();
        if (target == null) {
            return false;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return false;
        }

        WorldServer world = server.worldServerForDimension(target.getDimension());
        if (world == null) {
            return false;
        }

        return releaseAtCoordinates(world, target.getX(), target.getY(), target.getZ(), buffer, playerIdentity, reason + "-remembered-fallback");
    }

    private static boolean releaseAtCoordinates(World world, double x, double y, double z, ChainPlayerDropBuffer buffer, String playerIdentity, String reason) {
        if (world == null || buffer == null || buffer.isEmpty()) {
            return false;
        }

        List<ItemStack> drops = buffer.drain();
        MyMod.LOG.debug("[ChainDropCollector] Releasing {} buffered drop stack(s) for player {} reason={} at ({}, {}, {})",
            Integer.valueOf(drops.size()), playerIdentity, reason, Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        for (int i = 0; i < drops.size(); i++) {
            ItemStack itemStack = drops.get(i);
            try {
                EntityItem entityItem = new EntityItem(world, x, y, z, itemStack);
                // forceSpawn=true 允许在未加载区块中尝试生成；拒绝或异常时回填当前项及剩余掉落。
                entityItem.forceSpawn = true;
                if (!world.spawnEntityInWorld(entityItem)) {
                    restoreUnreleasedDrops(buffer, drops, i);
                    // 记录区块坐标、实体规模与世界侧性，供生成拒绝诊断。
                    int chunkX = (int) Math.floor(x / 16.0);
                    int chunkZ = (int) Math.floor(z / 16.0);
                    MyMod.LOG.debug("[ChainDropCollector] Failed to spawn buffered drop stack for player {} reason={} restoredDrops={} chunkExists=({},{}) loadedEntityList.size()={} world.isRemote={} at=({},{},{})",
                        playerIdentity, reason, Integer.valueOf(drops.size() - i),
                        Integer.valueOf(chunkX), Integer.valueOf(chunkZ),
                        Integer.valueOf(world.loadedEntityList.size()), Boolean.valueOf(world.isRemote),
                        Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
                    return false;
                }
            } catch (RuntimeException e) {
                restoreUnreleasedDrops(buffer, drops, i);
                MyMod.LOG.warn("[ChainDropCollector] Exception while spawning buffered drop stack for player {} reason={} restoredDrops={}",
                    playerIdentity, reason, Integer.valueOf(drops.size() - i), e);
                return false;
            }
        }
        return true;
    }

    private static void restoreUnreleasedDrops(ChainPlayerDropBuffer buffer, List<ItemStack> drops, int startIndex) {
        if (buffer == null || drops == null) {
            return;
        }

        for (int i = Math.max(0, startIndex); i < drops.size(); i++) {
            buffer.add(drops.get(i));
        }
    }

    private static WorldServer resolveRespawnWorld(EntityPlayer player) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || player == null) {
            return null;
        }

        int dimension = player.dimension;
        WorldServer world = server.worldServerForDimension(dimension);
        if (world == null) {
            return null;
        }

        if (!world.provider.canRespawnHere()) {
            int respawnDimension = player instanceof EntityPlayerMP
                ? world.provider.getRespawnDimension((EntityPlayerMP) player)
                : 0;
            world = server.worldServerForDimension(respawnDimension);
        }
        return world;
    }

    private static WorldServer resolveFallbackWorld(EntityPlayer player) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return null;
        }

        if (player != null && player.worldObj instanceof WorldServer) {
            return (WorldServer) player.worldObj;
        }
        return server.worldServerForDimension(0);
    }

    private static ChunkCoordinates resolveRespawnCoordinates(EntityPlayer player, WorldServer world) {
        if (player == null || world == null) {
            return null;
        }

        ChunkCoordinates bedLocation = player.getBedLocation(world.provider.dimensionId);
        boolean forced = player.isSpawnForced(world.provider.dimensionId);
        if (bedLocation == null) {
            return null;
        }
        return EntityPlayer.verifyRespawnCoordinates(world, bedLocation, forced);
    }
}
