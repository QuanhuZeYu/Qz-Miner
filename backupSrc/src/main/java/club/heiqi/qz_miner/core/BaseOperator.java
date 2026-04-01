package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.Constant;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import club.heiqi.qz_miner.utils.MessageUtils;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class BaseOperator {
    public Logger LOG = LogManager.getLogger();

    public EntityPlayerMP playerMP;

    public Manager manager;
    public BasePositionFounder positionFounder;
    public LinkedBlockingQueue<Vector3i> canBreakPositions = new LinkedBlockingQueue<>();

    public BaseOperator(Vector3i pos, Manager manager) {
        // 部分兼容性检查
        compatibilityCheck();

        this.playerMP = manager.player;
        this.manager = manager;
        // 根据缓存中的模式选取合适的搜索器
        this.positionFounder = manager.minerModeState.createPositionFounder(
                pos,
                canBreakPositions,
                playerMP,
                manager.pConfig
        );
        MyMod.parallelTick.addPreServerTickTask(this.positionFounder);
    }

    public long startTime;
    public int operatorCount = 0; // 包含自己挖的那一个
    @SubscribeEvent
    public void operatorTask(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;  // 仅在开始阶段处理任务
        if (!manager.inPressChainKey) {
            this.unRegistry();
        }

        if (canBreakPositions.isEmpty()) {
            return;
        }

        int breakCountInTick = 0;
        Vector3i pos;
        while ((pos = canBreakPositions.poll()) != null) {
            if (!checkCanOperate()) {
                this.unRegistry();
                return;
            }

            // 在执行过程中 playerMP.playerNetServerHandler 可能因各种原因变为 null
            try {
                playerMP.theItemInWorldManager.tryHarvestBlock(pos.x, pos.y, pos.z);
            } catch (Exception e) {
                String errorInfo = "尝试采掘方块时出现异常:\n"+e;
                LOG.error(errorInfo);
                MessageUtils.serverSendPlayerMessage(errorInfo,manager.playerUUID);
            }

            breakCountInTick++;
            operatorCount++;
            if (breakCountInTick >= 64) {
                return;
            }
        }

        if (positionFounder.stopped.get()) {
            this.unRegistry();
        }
    }

    /**
     * 未按连锁键 或者 装备耐久不足1 返回 false
     */
    public boolean checkCanOperate() {
        if (!manager.inPressChainKey) return false;

        ItemStack equippedItem = playerMP.getCurrentEquippedItem();
        if (equippedItem != null && equippedItem.isItemStackDamageable()) {
            return (equippedItem.getMaxDamage() - equippedItem.getItemDamage() > 1);
        }

        return true;
    }


    public void registry() {
        startTime = System.currentTimeMillis();
        FMLCommonHandler.instance().bus().register(this);
        // LOG.info("连锁执行器注册成功 {}", playerMP.getDisplayName());
    }
    public void unRegistry() {
        long totalTime = System.currentTimeMillis() - startTime;
        // 通过UUID发送消息，避免玩家为null
        if (manager.pConfig.useChainDoneMessage)
            MessageUtils.serverSendPlayerMessage(
                    "连锁完毕; 挖掘数量: "+ operatorCount +
                            "; 连锁用时: "+convertMillisToSeconds(totalTime), manager.playerUUID
            );
        FMLCommonHandler.instance().bus().unregister(this);
        manager.inOperate = false;
        // 终止搜索器
        positionFounder.interrupt();
        // LOG.info("连锁执行器注销成功 {}", playerMP.getDisplayName());
    }
    public static float convertMillisToSeconds(long millis) {
        return Math.round(millis / 10.0) / 100.0f;
    }


    // ========== 兼容性检查 ==========
    public static boolean compatibilityChecked = false;
    public static void compatibilityCheck() {
        if (compatibilityChecked) return;
        hasVP_API();
        compatibilityChecked = true;
    }
    public static boolean hasVP_API = false;
    public static void hasVP_API() {
        try {
            Class<?> clazz = Class.forName("com.sinthoras.visualprospecting.VisualProspecting_API");
            hasVP_API = true;
        } catch (ClassNotFoundException e) {
            Constant.LOG.warn("未检测到 VisualProspecting_API");
            hasVP_API = false;
        }
    }
}
