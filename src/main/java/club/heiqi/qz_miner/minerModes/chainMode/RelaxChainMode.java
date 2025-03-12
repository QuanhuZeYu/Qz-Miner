package club.heiqi.qz_miner.minerModes.chainMode;

import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounderRelaxed;
import org.joml.Vector3i;

public class RelaxChainMode extends BaseChainMode {
    public RelaxChainMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        positionFounder = new ChainFounderRelaxed(this, center, modeManager.player);
    }
}
