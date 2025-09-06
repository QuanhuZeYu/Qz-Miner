package club.heiqi.qz_miner.core.opertator;

import club.heiqi.qz_miner.core.Manager;
import club.heiqi.qz_miner.core.founder.BaseChainPositionFounder;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import org.joml.Vector3i;

public class BaseChainOperator extends BaseOperator {
    public BaseChainOperator(Vector3i pos, Manager manager) {
        super(pos, manager);
    }
    @Override
    public BasePositionFounder createPositionFounder() {
        return new BaseChainPositionFounder(
                pos,
                canBreakPositions,
                playerMP,
                manager.pConfig
        );
    }
}
