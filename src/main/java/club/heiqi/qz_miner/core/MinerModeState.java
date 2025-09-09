package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.core.founder.*;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class MinerModeState {
    public static Logger LOG = LogManager.getLogger();
    public static final String[] MAIN_MODE= {
            "qz_miner.textTips.rangeMode",      // 0 爆破模式
            "qz_miner.textTips.chainMode",      // 1 连锁模式
            "qz_miner.textTips.interactMode",   // 2 交互模式
    };

    public static final String[] RANGE_MODE = {
            "qz_miner.textTips.rangeMode.blindBlastMode",       // 无差别爆破模式 0
            "qz_miner.textTips.rangeMode.screenBlastingMode",   // 筛选爆破模式 1
            "qz_miner.textTips.rangeMode.tunnelBlastingMode",   // 隧道爆破模式 2
            "qz_miner.textTips.rangeMode.oreBlastingMode",      // 矿石爆破模式 3
            "qz_miner.textTips.rangeMode.blastingLoggingMode",  // 爆破伐木模式 4
    };

    public static final String[] CHAIN_MODE = {
            "qz_miner.textTips.chainMode.baseChainMode",        // 基础连锁模式 0
    };

    public static final String[] INTERACT_MODE = {
            "qz_miner.textTips.chainMode.baseChainMode",        // 基础连锁模式 0
            "qz_miner.textTips.rangeMode.blindBlastMode",       // 无差别爆破模式 1
            "qz_miner.textTips.rangeMode.screenBlastingMode",   // 筛选爆破模式 2
    };

    public int mainMode = 1;
    public int rangeMode = 0;
    public int chainMode = 0;
    public int interactMode = 0;

    public boolean isInteractMode() {
        return mainMode == 2;
    }

    // ========== 主模式 ==========
    public String nextMainMode() {
        mainMode = (mainMode + 1) % MAIN_MODE.length;
        return currentMainMode();
    }

    public String previousMainMode() {
        mainMode = (mainMode - 1 + MAIN_MODE.length) % MAIN_MODE.length; // 修复负索引问题
        return currentMainMode();
    }

    public String currentMainMode() {
        return MAIN_MODE[mainMode];
    }

    // ========== 次模式 ==========
    public String nextSecondMode() {
        switch (mainMode) { // 0
            case 1 -> {
                return nextChainMode();
            }
            case 2 -> {
                return nextInteractMode();
            }
            default -> {
                return nextRangeMode();
            }
        }
    }

    public String previousSecondMode() {
        switch (mainMode) { // 0
            case 1 -> {
                return previousChainMode();
            }
            case 2 -> {
                return previousInteractMode();
            }
            default -> {
                return previousRangeMode();
            }
        }
    }

    public String currentSecondMode() {
        switch (mainMode) { // 0
            case 1 -> {
                return currentChainMode();
            }
            case 2 -> {
                return currentInteractMode();
            }
            default -> {
                return currentRangeMode();
            }
        }
    }

    // ========== 范围模式 ==========
    public String nextRangeMode() {
        rangeMode = (rangeMode + 1) % RANGE_MODE.length;
        return currentRangeMode();
    }

    public String previousRangeMode() {
        rangeMode = (rangeMode - 1 + RANGE_MODE.length) % RANGE_MODE.length; // 修复负索引问题
        return currentRangeMode();
    }

    public String currentRangeMode() {
        return RANGE_MODE[rangeMode];
    }

    // ========== 连锁模式 ==========
    public String nextChainMode() {
        chainMode = (chainMode + 1) % CHAIN_MODE.length;
        return currentChainMode();
    }

    public String previousChainMode() {
        chainMode = (chainMode - 1 + CHAIN_MODE.length) % CHAIN_MODE.length; // 修复负索引问题
        return currentChainMode();
    }

    public String currentChainMode() {
        return CHAIN_MODE[chainMode];
    }

    // ========== 交互模式 ==========
    public String nextInteractMode() {
        interactMode = (interactMode + 1) % INTERACT_MODE.length;
        return currentChainMode();
    }

    public String previousInteractMode() {
        interactMode = (interactMode - 1 + INTERACT_MODE.length) % INTERACT_MODE.length; // 修复负索引问题
        return currentChainMode();
    }

    public String currentInteractMode() {
        return INTERACT_MODE[interactMode];
    }

    public BasePositionFounder createPositionFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig config) {
        switch (mainMode) {
            case 1 -> {
                switch (chainMode) {
                    default -> { // 0
                        return new ChainPositionFounder(center, results, player, config);
                    }
                }
            }
            case 2 -> {
                switch (interactMode) {
                    case 1 -> {
                        return new BasePositionFounder(center, results, player, config);
                    }
                    case 2 -> {
                        return new ScreenBlastingFounder(center, results, player, config);
                    }
                    default -> { // 0
                        return new ChainPositionFounder(center, results, player, config);
                    }
                }
            }
            default -> { // 0
                switch (rangeMode) {
                    case 1 -> {
                        return new ScreenBlastingFounder(center, results, player, config);
                    }
                    case 2 -> {
                        return new TunnelBlastingFounder(center, results, player, config);
                    }
                    case 3 -> {
                        return new OreBlastingFounder(center, results, player, config);
                    }
                    case 4 -> {
                        return new BlastingLoggingFounder(center, results, player, config);
                    }
                    default -> { // 0
                        return new BasePositionFounder(center, results, player, config);
                    }
                }
            }
        }
    }
}
