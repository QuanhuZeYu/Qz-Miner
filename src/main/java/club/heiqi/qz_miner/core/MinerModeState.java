package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.core.founder.BaseChainPositionFounder;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import club.heiqi.qz_miner.core.opertator.BaseChainOperator;
import club.heiqi.qz_miner.core.opertator.BaseOperator;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class MinerModeState {
    public static final String[] MAIN_MODE= {
            "qz_miner.textTips.rangeMode",
            "qz_miner.textTips.chainMode"
    };

    public int mainMode = 0;

    public String nextMainMode() {
        mainMode = (mainMode+1) % MAIN_MODE.length;
        return MAIN_MODE[mainMode];
    }
    public String previousMainMode() {
        mainMode = (mainMode-1) % MAIN_MODE.length;
        return MAIN_MODE[mainMode];
    }
    public String currentMainMode() {
        return MAIN_MODE[mainMode];
    }

    public BaseOperator createOperator(Vector3i center, Manager manager) {
        switch (mainMode) {
            case 1 -> {
                return new BaseChainOperator(center, manager);
            }
            default -> {
                return new BaseOperator(center, manager);
            }
        }
    }

    public BasePositionFounder createPositionFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, Manager.MinerConfig config) {
        switch (mainMode) {
            case 1 -> {
                return new BaseChainPositionFounder(center, results, player, config);
            }
            default -> {
                return new BasePositionFounder(center, results, player, config);
            }
        }
    }
}
