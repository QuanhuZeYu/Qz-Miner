package club.heiqi.qz_miner.compat.adapter;

import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.world.World;

/**
 * 不可用扫雷适配器。
 */
public final class NoOpMinesweeperCompatAdapter implements MinesweeperCompatAdapter {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean isMinesweeperTarget(World world, ChainTarget target) {
        return false;
    }

    @Override
    public List<ChainTarget> collectBombTargets(World world, ChainTarget target, int radius, int maxTargets) {
        return Collections.emptyList();
    }
}
