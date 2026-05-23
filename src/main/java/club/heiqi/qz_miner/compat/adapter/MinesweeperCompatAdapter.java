package club.heiqi.qz_miner.compat.adapter;

import java.util.List;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.world.World;

/**
 * 扫雷棋盘兼容适配器。
 */
public interface MinesweeperCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断目标坐标是否属于扫雷棋盘。
     *
     * @param world 当前世界
     * @param target 当前瞄准坐标
     * @return 是否属于扫雷棋盘
     */
    boolean isMinesweeperTarget(World world, ChainTarget target);

    /**
     * 收集指定扫描半径内的雷方块坐标。
     *
     * @param world 当前世界
     * @param target 当前瞄准坐标
     * @param radius 扫描半径
     * @param maxTargets 最大返回数量
     * @return 雷方块坐标列表
     */
    List<ChainTarget> collectBombTargets(World world, ChainTarget target, int radius, int maxTargets);
}
