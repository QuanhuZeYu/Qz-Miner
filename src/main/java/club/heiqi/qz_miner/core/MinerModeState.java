package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.core.founder.ChainPositionFounder;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import club.heiqi.qz_miner.core.founder.ScreenBlastingMode;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class MinerModeState {
    public static Logger LOG = LogManager.getLogger();
    public static final String[] MAIN_MODE= {
            "qz_miner.textTips.rangeMode",
            "qz_miner.textTips.chainMode"
    };

    public static final String[] RANGE_MODE = {
            "qz_miner.textTips.rangeMode.blindBlastMode",       // 无差别爆破模式
            "qz_miner.textTips.rangeMode.screenBlastingMode",   // 筛选爆破模式
            "qz_miner.textTips.rangeMode.tunnelBlastingMode",   // 隧道爆破模式
    };

    public static final String[] CHAIN_MODE = {
            "qz_miner.textTips.chainMode.baseChainMode",            // 基础连锁模式
            "qz_miner.textTips.chainMode.chainSawOperationModel",   // 伐木连锁模式
    };

    public int mainMode = 1;
    public int rangeMode = 0;
    public int chainMode = 0;

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
        return (mainMode == 1) ? nextChainMode() : nextRangeMode();
    }

    public String previousSecondMode() {
        return (mainMode == 1) ? previousChainMode() : previousRangeMode();
    }

    public String currentSecondMode() {
        return (mainMode == 1) ? currentChainMode() : currentRangeMode();
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

    public BasePositionFounder createPositionFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig config) {
        if (mainMode == 1) {
            switch (chainMode) {
                default -> { // 0
                    return new ChainPositionFounder(center, results, player, config);
                }
            }
        }
        else {
            switch (rangeMode) {
                case 1 -> {
                    return new ScreenBlastingMode(center, results, player, config);
                }
                default -> { // 0
                    return new BasePositionFounder(center, results, player, config);
                }
            }
        }
    }
}
