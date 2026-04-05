package club.heiqi.qz_miner.compat.lootgames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import ru.timeconqueror.lootgames.api.block.BoardBorderBlock;
import ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock;
import ru.timeconqueror.lootgames.common.block.tile.MSMasterTile;
import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;
import ru.timeconqueror.lootgames.minigame.minesweeper.MSBoard;
import ru.timeconqueror.lootgames.minigame.minesweeper.Type;
import ru.timeconqueror.lootgames.utils.future.BlockPos;
import ru.timeconqueror.lootgames.utils.future.BlockState;

/**
 * LootGames 扫雷兼容工具。
 */
public final class LootGamesMinesweeperHelper {

    private LootGamesMinesweeperHelper() {}

    /**
     * 判断当前瞄准方块是否属于扫雷棋盘。
     *
     * @param world 当前世界
     * @param target 当前瞄准坐标
     * @return 是否属于扫雷棋盘
     */
    public static boolean isMinesweeperTarget(World world, ChainTarget target) {
        return resolveMasterTile(world, target) != null;
    }

    /**
     * 收集指定扫描半径内的雷方块坐标。
     *
     * @param world 当前世界
     * @param target 当前瞄准坐标
     * @param radius 扫描半径
     * @param maxTargets 最大返回数量
     * @return 雷方块坐标列表
     */
    public static List<ChainTarget> collectBombTargets(World world, ChainTarget target, int radius, int maxTargets) {
        MSMasterTile masterTile = resolveMasterTile(world, target);
        if (masterTile == null) {
            return Collections.emptyList();
        }

        GameMineSweeper game = masterTile.getGame();
        if (game == null || !game.isBoardGenerated()) {
            return Collections.emptyList();
        }

        MSBoard board = game.getBoard();
        int boardSize = board.size();
        int cappedMaxTargets = Math.max(1, maxTargets);
        List<ChainTarget> result = new ArrayList<ChainTarget>(Math.min(boardSize * boardSize, cappedMaxTargets));

        for (int x = 0; x < boardSize && result.size() < cappedMaxTargets; x++) {
            for (int y = 0; y < boardSize && result.size() < cappedMaxTargets; y++) {
                if (board.getType(x, y) != Type.BOMB) {
                    continue;
                }

                BlockPos bombPos = game.convertToBlockPos(new ru.timeconqueror.lootgames.api.util.Pos2i(x, y));
                if (!isWithinPreviewRadius(target, bombPos, radius)) {
                    continue;
                }

                result.add(new ChainTarget(bombPos.getX(), bombPos.getY(), bombPos.getZ()));
            }
        }

        return result;
    }

    private static boolean isWithinPreviewRadius(ChainTarget target, BlockPos blockPos, int radius) {
        return Math.abs(blockPos.getX() - target.getX()) <= radius
            && Math.abs(blockPos.getY() - target.getY()) <= radius
            && Math.abs(blockPos.getZ() - target.getZ()) <= radius;
    }

    @Nullable
    private static MSMasterTile resolveMasterTile(World world, ChainTarget target) {
        if (world == null || target == null) {
            return null;
        }

        TileEntity tileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (tileEntity instanceof MSMasterTile) {
            return (MSMasterTile) tileEntity;
        }

        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null) {
            return null;
        }

        BlockPos pos = BlockPos.of(target.getX(), target.getY(), target.getZ());
        BlockPos masterPos = null;
        if (block instanceof SmartSubordinateBlock) {
            masterPos = SmartSubordinateBlock.getMasterPos(world, pos);
        } else if (block instanceof BoardBorderBlock) {
            int meta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
            masterPos = BoardBorderBlock.getMasterPos(world, pos, BlockState.of(block, meta));
        }

        if (masterPos == null) {
            return null;
        }

        TileEntity masterTile = world.getTileEntity(masterPos.getX(), masterPos.getY(), masterPos.getZ());
        return masterTile instanceof MSMasterTile ? (MSMasterTile) masterTile : null;
    }
}
