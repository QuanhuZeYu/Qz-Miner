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
     * 在玩家不可用时，尝试将缓冲掉落释放到重生点或世界出生点。
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
     * 仅记录当前玩家可用的兜底释放坐标，不立即释放掉落。
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

    /**
     * 无法恢复上下文时清空缓冲并打印警告。
     *
     * @param playerIdentity 玩家标识
     * @param buffer 掉落缓冲
     * @param reason 清理原因
     */
    public static void discard(String playerIdentity, ChainPlayerDropBuffer buffer, String reason) {
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        int pending = buffer.size();
        buffer.clear();
        MyMod.LOG.warn("[ChainDropCollector] Discarded {} buffered drop stack(s) for player {} reason={}",
            Integer.valueOf(pending), playerIdentity, reason);
    }

    private static boolean releaseAtCoordinates(World world, double x, double y, double z, ChainPlayerDropBuffer buffer, String playerIdentity, String reason) {
        if (world == null || buffer == null || buffer.isEmpty()) {
            return false;
        }

        List<ItemStack> drops = buffer.drain();
        MyMod.LOG.debug("[ChainDropCollector] Releasing {} buffered drop stack(s) for player {} reason={} at ({}, {}, {})",
            Integer.valueOf(drops.size()), playerIdentity, reason, Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        for (ItemStack itemStack : drops) {
            world.spawnEntityInWorld(new EntityItem(world, x, y, z, itemStack));
        }
        return true;
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
