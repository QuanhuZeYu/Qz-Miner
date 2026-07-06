package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerDropBuffer;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

/**
 * 连锁掉落聚合器。
 */
public class ChainDropCollector {

    /**
     * world-tick 掉落释放连续失败上限（守 NORTH_STAR 信条四四级降级链终点）。
     *
     * <p>{@code onWorldTick} 释放路径四级降级链（当前位置→重生/出生点→已记忆兜底→discard）
     * 全部失败时累加玩家级失败计数，达此上限才触发 {@code ChainDropReleaseHelper.discard} 兜底丢弃，
     * 避免单次 spawn 失败导致每帧死循环重试（实机曾 35 秒刷 1.8 万行直到 ServerStopped）。
     * 1.7.10 服务端 20 TPS，100 次 ≈ 5 秒，留足降级链恢复窗口又不致长时间吞物品。</p>
     */
    private static final int DROP_RELEASE_MAX_RETRIES = 100;

    public ChainDropCollector() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        if (!(event.harvester instanceof EntityPlayerMP) || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getPlayerState(event.harvester.getUniqueID());
        if (playerState == null) {
            return;
        }
        boolean executing = playerState.isExecuting();
        // 起点方块掉落捕获：原版 tryHarvestBlock 同 tick 同步触发 HarvestDropsEvent 早于 collector 执行窗口，
        // armed 一次后此处也收；consume 必须只调一次（一次性语义）。
        boolean seedArmed = playerState.consumeSeedDropCaptureIfArmed(ChainTickSource.currentServerTick());
        if (!executing && !seedArmed) {
            return;
        }

        ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
        ChainDropReleaseHelper.rememberRespawnOrWorldSpawn(event.harvester, dropBuffer);
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent player={} status={} executing={} seedArmed={} rawDropStacks={} pendingBefore={}",
            event.harvester.getUniqueID(), playerState.getExecutionStatus(), executing, seedArmed, event.drops.size(), dropBuffer.size());
        dropBuffer.addAll(event.drops);
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent merged player={} pendingAfter={}",
            event.harvester.getUniqueID(), dropBuffer.size());
        event.drops.clear();
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START
            || event.world == null
            || event.world.isRemote
            || MyMod.chainStateService == null
            || MyMod.playerManager == null) {
            return;
        }

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
            if (playerState.getExecutionStatus() != ChainExecutionStatus.IDLE
                || dropBuffer.isEmpty()) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                // 玩家缺失分支（LOGOUT/异步延迟导致 playerManager 暂时拿不到 EntityPlayerMP）：
                // 与玩家存在分支对称，二级 respawn/出生点 → 三级 已记忆兜底 → 全链失败累加，
                // 达 DROP_RELEASE_MAX_RETRIES 才 discard 兜底，防 buffer 残留每帧非空无限刷屏。
                String missingPlayerUUID = playerState.getPlayerUUID().toString();

                // 二级：玩家重生点或世界出生点（玩家非 MP 实例时退一步用 player 句柄尝试）
                if (player != null && ChainDropReleaseHelper.releaseAtRespawnOrWorldSpawn(player, dropBuffer, "world-tick-missing-player")) {
                    playerState.resetDropReleaseFailure();
                    continue;
                }
                // 三级：已记忆的兜底坐标（onHarvestDrops 起手 rememberRespawnOrWorldSpawn 预存）
                if (ChainDropReleaseHelper.releaseAtRememberedTarget(dropBuffer, missingPlayerUUID, "world-tick-missing-player")) {
                    // 阶段8 块3：删旧 syncPlayerState（八字段同步链已删，掉落释放后无状态需同步客户端）。
                    playerState.resetDropReleaseFailure();
                    continue;
                }
                // 全链失败：累加失败计数，达上限 discard 兜底（与玩家存在分支对称，守信条四终点）
                int failures = playerState.incrementDropReleaseFailure();
                if (failures >= DROP_RELEASE_MAX_RETRIES) {
                    ChainDropReleaseHelper.discard(missingPlayerUUID, dropBuffer, "world-tick-missing-exhausted");
                    playerState.resetDropReleaseFailure();
                } else {
                    MyMod.LOG.warn("[ChainDropCollector] Missing EntityPlayerMP for {}, keeping {} pending drop stack(s) (consecutive failures={}/{})",
                        playerState.getPlayerUUID(), dropBuffer.size(), failures, DROP_RELEASE_MAX_RETRIES);
                }
                continue;
            }

            MyMod.LOG.debug("[ChainDropCollector] Ready to release aggregated drops for player {}, pending aggregated stacks={}",
                playerState.getPlayerUUID(), dropBuffer.size());
            // 信条四四级降级链：当前位置 → 重生/出生点 → 已记忆兜底 → 连续失败超上限 discard 兜底。
            // 实机 BUG 根因：原仅接通第一级，releaseAtCoordinates spawn 失败时 restoreUnreleasedDrops 回填 buffer，
            // 下帧再次非空再次同坐标失败回填 → 死循环（35 秒刷 1.8 万行）。此重构补全四级至 discard 终点。
            String playerUUID = playerState.getPlayerUUID().toString();

            // 一级：玩家当前位置
            if (ChainDropReleaseHelper.releaseAtPlayer((EntityPlayerMP) player, dropBuffer, "world-tick")) {
                playerState.resetDropReleaseFailure();
                continue;
            }
            // 二级：玩家重生点或世界出生点（玩家在线但因坐标 chunk 未加载等导致当前位置释放失败）
            if (ChainDropReleaseHelper.releaseAtRespawnOrWorldSpawn(player, dropBuffer, "world-tick-respawn")) {
                playerState.resetDropReleaseFailure();
                continue;
            }
            // 三级：已记忆的兜底坐标（onHarvestDrops 起手 rememberRespawnOrWorldSpawn 预存）
            if (ChainDropReleaseHelper.releaseAtRememberedTarget(dropBuffer, playerUUID, "world-tick-remembered")) {
                playerState.resetDropReleaseFailure();
                continue;
            }
            // 四级：全链失败累加，达上限 discard 兜底丢弃（守信条四终点），防每帧死循环重试
            int failures = playerState.incrementDropReleaseFailure();
            if (failures >= DROP_RELEASE_MAX_RETRIES) {
                ChainDropReleaseHelper.discard(playerUUID, dropBuffer, "world-tick-release-exhausted");
                playerState.resetDropReleaseFailure();
            } else {
                // 玩家存在分支对称 WARN 摘要（与玩家缺失分支 :105-106 一致，含 consecutive failures 计数）
                MyMod.LOG.warn("[ChainDropCollector] All 4-level release failed for player {}, keeping {} pending drop stack(s) (consecutive failures={}/{})",
                    playerState.getPlayerUUID(), dropBuffer.size(), failures, DROP_RELEASE_MAX_RETRIES);
            }
            // 未达上限时 buffer 仍非空（restoreUnreleasedDrops 已回填），下帧重试；达上限后 buffer 已清空，自然跳过。
        }
    }
}
