package club.heiqi.qz_miner.compat.adapter.lootgames;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.compat.adapter.ClassNameCompatSupport;
import club.heiqi.qz_miner.compat.adapter.MinesweeperCompatAdapter;
import club.heiqi.qz_miner.compat.adapter.ReflectiveMemberSupport;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * LootGames 扫雷兼容适配器。
 */
public final class LootGamesMinesweeperCompatAdapter implements MinesweeperCompatAdapter {

    private static final int MAX_MASTER_SEARCH_DISTANCE = 128;
    private static final int BORDER_META_HORIZONTAL = 0;
    private static final int BORDER_META_VERTICAL = 1;
    private static final int BORDER_META_TOP_LEFT = 2;
    private static final int BORDER_META_TOP_RIGHT = 3;
    private static final int BORDER_META_BOTTOM_RIGHT = 4;
    private static final int BORDER_META_BOTTOM_LEFT = 5;

    private final Class<?> masterTileType;
    private final Class<?> subordinateProviderType;
    private final Class<?> smartSubordinateBlockType;
    private final Class<?> boardBorderBlockType;
    private final Field gameField;
    private final Field boardField;
    private final Field boardSizeField;
    private final Field boardFieldsField;
    private final Field boardCellTypeField;
    private final Method masterTileGetGameMethod;
    private final Method gameIsBoardGeneratedMethod;
    private final Method gameGetBoardMethod;
    private final Method gameCurrentBoardSizeMethod;
    private final Method gameAllocatedBoardSizeMethod;
    private final Method boardSizeMethod;
    private final Method boardGetTypeMethod;
    private final Object bombType;
    private final boolean available;
    private volatile boolean runtimeDisabled;
    private volatile boolean reflectionFailureLogged;

    /**
     * 创建 LootGames 扫雷反射适配器。
     */
    public LootGamesMinesweeperCompatAdapter() {
        Class<?> resolvedMasterTileType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.common.block.tile.MSMasterTile");
        Class<?> resolvedGameMineSweeperType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper");
        Class<?> resolvedBoardType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.minigame.minesweeper.MSBoard");
        Class<?> resolvedBoardCellType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.minigame.minesweeper.MSBoard$MSField");
        Class<?> resolvedTypeEnumType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.minigame.minesweeper.Type");
        Class<?> resolvedSubordinateProviderType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.block.ISubordinateProvider");
        Class<?> resolvedSmartSubordinateBlockType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock");
        Class<?> resolvedBoardBorderBlockType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.block.BoardBorderBlock");

        this.masterTileType = resolvedMasterTileType;
        this.subordinateProviderType = resolvedSubordinateProviderType;
        this.smartSubordinateBlockType = resolvedSmartSubordinateBlockType;
        this.boardBorderBlockType = resolvedBoardBorderBlockType;
        this.gameField = resolveField(resolvedMasterTileType, "game");
        this.boardField = resolveField(resolvedGameMineSweeperType, "board");
        this.boardSizeField = resolveField(resolvedBoardType, "size");
        this.boardFieldsField = resolveField(resolvedBoardType, "board");
        this.boardCellTypeField = resolveField(resolvedBoardCellType, "type");
        this.masterTileGetGameMethod = resolveMethod(resolvedMasterTileType, "getGame");
        this.gameIsBoardGeneratedMethod = resolveMethod(resolvedGameMineSweeperType, "isBoardGenerated");
        this.gameGetBoardMethod = resolveMethod(resolvedGameMineSweeperType, "getBoard");
        this.gameCurrentBoardSizeMethod = resolveMethod(resolvedGameMineSweeperType, "getCurrentBoardSize");
        this.gameAllocatedBoardSizeMethod = resolveMethod(resolvedGameMineSweeperType, "getAllocatedBoardSize");
        this.boardSizeMethod = resolveMethod(resolvedBoardType, "size");
        this.boardGetTypeMethod = resolveMethod(resolvedBoardType, "getType", int.class, int.class);
        this.bombType = resolveStaticField(resolvedTypeEnumType, "BOMB");
        this.available = resolvedMasterTileType != null
            && resolvedGameMineSweeperType != null
            && resolvedBoardType != null
            && resolvedBoardCellType != null
            && resolvedTypeEnumType != null
            && resolvedSubordinateProviderType != null
            && resolvedSmartSubordinateBlockType != null
            && resolvedBoardBorderBlockType != null
            && (gameField != null || masterTileGetGameMethod != null)
            && gameIsBoardGeneratedMethod != null
            && (boardField != null || gameGetBoardMethod != null)
            && (gameCurrentBoardSizeMethod != null || boardSizeField != null || boardSizeMethod != null)
            && (boardFieldsField != null && boardCellTypeField != null || boardGetTypeMethod != null)
            && bombType != null;
    }

    @Override
    public boolean isAvailable() {
        return available && !runtimeDisabled;
    }

    @Override
    public boolean isMinesweeperTarget(World world, ChainTarget target) {
        return resolveMasterTile(world, target) != null;
    }

    @Override
    public List<ChainTarget> collectBombTargets(World world, ChainTarget target, int radius, int maxTargets) {
        MasterTileResolution resolution = resolveMasterTile(world, target);
        if (resolution == null) {
            return Collections.emptyList();
        }

        Object game = readField(gameField, resolution.masterTile);
        if (game == null) {
            game = invoke(masterTileGetGameMethod, resolution.masterTile);
        }
        if (game == null || !asBoolean(invoke(gameIsBoardGeneratedMethod, game))) {
            return Collections.emptyList();
        }

        Object board = readField(boardField, game);
        if (board == null) {
            board = invoke(gameGetBoardMethod, game);
        }
        int boardSize = readCurrentBoardSize(game, board);
        if (board == null || boardSize <= 0) {
            return Collections.emptyList();
        }
        int allocatedBoardSize = readAllocatedBoardSize(game, boardSize);
        int cappedMaxTargets = Math.max(1, maxTargets);
        List<ChainTarget> result = new ArrayList<ChainTarget>(Math.min(boardSize * boardSize, cappedMaxTargets));
        ChainTarget boardOrigin = resolveBoardOrigin(resolution.masterTarget, boardSize, allocatedBoardSize);

        for (int x = 0; x < boardSize && result.size() < cappedMaxTargets; x++) {
            for (int y = 0; y < boardSize && result.size() < cappedMaxTargets; y++) {
                Object cellType = readBoardCellType(board, x, y);
                if (cellType == null || !bombType.equals(cellType)) {
                    continue;
                }

                ChainTarget bombTarget = new ChainTarget(boardOrigin.getX() + x, boardOrigin.getY(), boardOrigin.getZ() + y);
                if (!isWithinPreviewRadius(target, bombTarget, radius)) {
                    continue;
                }

                result.add(bombTarget);
            }
        }

        return result;
    }

    private boolean isWithinPreviewRadius(ChainTarget target, ChainTarget candidate, int radius) {
        if (target == null || candidate == null) {
            return false;
        }

        return Math.abs(candidate.getX() - target.getX()) <= radius
            && Math.abs(candidate.getY() - target.getY()) <= radius
            && Math.abs(candidate.getZ() - target.getZ()) <= radius;
    }

    private MasterTileResolution resolveMasterTile(World world, ChainTarget target) {
        if (!isAvailable() || world == null || target == null) {
            return null;
        }

        TileEntity tileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (masterTileType.isInstance(tileEntity)) {
            return new MasterTileResolution(tileEntity, target);
        }

        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null) {
            return null;
        }

        ChainTarget masterTarget = null;
        if (isSubordinateProviderBlock(block)) {
            masterTarget = resolveMasterTargetFromSmartSubordinate(world, target);
        } else if (boardBorderBlockType.isInstance(block)) {
            masterTarget = resolveMasterTargetFromBoardBorder(world, target, block, world.getBlockMetadata(target.getX(), target.getY(), target.getZ()));
        }

        if (masterTarget == null) {
            return null;
        }

        TileEntity masterTile = world.getTileEntity(masterTarget.getX(), masterTarget.getY(), masterTarget.getZ());
        return masterTileType.isInstance(masterTile) ? new MasterTileResolution(masterTile, masterTarget) : null;
    }

    private ChainTarget resolveMasterTargetFromSmartSubordinate(World world, ChainTarget target) {
        int searchX = target.getX();
        int searchZ = target.getZ();
        int remainingSteps = MAX_MASTER_SEARCH_DISTANCE;
        while (remainingSteps > 0 && (searchX == target.getX() && searchZ == target.getZ() || isSubordinateProviderBlock(world, searchX, target.getY(), searchZ))) {
            searchX--;
            remainingSteps--;
        }
        searchX++;

        while (remainingSteps > 0 && (searchX == target.getX() && searchZ == target.getZ() || isSubordinateProviderBlock(world, searchX, target.getY(), searchZ))) {
            searchZ--;
            remainingSteps--;
        }
        searchZ++;

        return new ChainTarget(searchX - 1, target.getY(), searchZ - 1);
    }

    private ChainTarget resolveMasterTargetFromBoardBorder(World world, ChainTarget target, Block startBlock, int startMeta) {
        int searchX = target.getX();
        int searchZ = target.getZ();
        Block currentBlock = startBlock;
        int currentMeta = startMeta;

        for (int i = 0; i < MAX_MASTER_SEARCH_DISTANCE && boardBorderBlockType.isInstance(currentBlock); i++) {
            if (currentMeta == BORDER_META_HORIZONTAL || currentMeta == BORDER_META_TOP_RIGHT) {
                searchX--;
            } else if (currentMeta == BORDER_META_BOTTOM_RIGHT || currentMeta == BORDER_META_BOTTOM_LEFT || currentMeta == BORDER_META_VERTICAL) {
                searchZ--;
            }

            currentBlock = world.getBlock(searchX, target.getY(), searchZ);
            currentMeta = world.getBlockMetadata(searchX, target.getY(), searchZ);
        }

        return new ChainTarget(searchX, target.getY(), searchZ);
    }

    private boolean isSubordinateProviderBlock(World world, int x, int y, int z) {
        return isSubordinateProviderBlock(world.getBlock(x, y, z));
    }

    private boolean isSubordinateProviderBlock(Block block) {
        return subordinateProviderType.isInstance(block)
            || smartSubordinateBlockType.isInstance(block);
    }

    private ChainTarget resolveBoardOrigin(ChainTarget masterTarget, int boardSize, int allocatedBoardSize) {
        int offset = Math.max(0, allocatedBoardSize - boardSize) / 2;
        return new ChainTarget(masterTarget.getX() + 1 + offset, masterTarget.getY(), masterTarget.getZ() + 1 + offset);
    }

    private int readCurrentBoardSize(Object game, Object board) {
        int currentBoardSize = asInt(invoke(gameCurrentBoardSizeMethod, game));
        if (currentBoardSize > 0) {
            return currentBoardSize;
        }

        int boardSize = asInt(readField(boardSizeField, board));
        if (boardSize > 0) {
            return boardSize;
        }
        return asInt(invoke(boardSizeMethod, board));
    }

    private Object readBoardCellType(Object board, int x, int y) {
        Object boardFields = readField(boardFieldsField, board);
        if (boardFields instanceof Object[][]) {
            Object[][] cells = (Object[][]) boardFields;
            if (x >= 0 && x < cells.length && y >= 0 && cells[x] != null && y < cells[x].length) {
                Object type = readField(boardCellTypeField, cells[x][y]);
                if (type != null) {
                    return type;
                }
            }
        }
        return invoke(boardGetTypeMethod, board, Integer.valueOf(x), Integer.valueOf(y));
    }

    private int readAllocatedBoardSize(Object game, int currentBoardSize) {
        int allocatedBoardSize = asInt(invoke(gameAllocatedBoardSizeMethod, game));
        return allocatedBoardSize > 0 ? allocatedBoardSize : currentBoardSize;
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    private Field resolveField(Class<?> ownerType, String fieldName) {
        return ReflectiveMemberSupport.findFieldInHierarchy(ownerType, fieldName);
    }

    private Method resolveMethod(Class<?> ownerType, String methodName, Class<?>... parameterTypes) {
        if (ownerType == null || methodName == null || methodName.isEmpty()) {
            return null;
        }

        return ReflectiveMemberSupport.findMethodInHierarchy(ownerType, methodName, parameterTypes);
    }

    private Object resolveStaticField(Class<?> ownerType, String fieldName) {
        if (ownerType == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        try {
            Field field = resolveField(ownerType, fieldName);
            if (field == null) {
                return null;
            }
            return field.get(null);
        } catch (IllegalAccessException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private Object readField(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }

        try {
            return field.get(target);
        } catch (IllegalAccessException | LinkageError | SecurityException e) {
            logReflectionFailure(e);
            return null;
        }
    }

    private Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException | LinkageError | SecurityException e) {
            logReflectionFailure(e);
            return null;
        }
    }

    private void logReflectionFailure(Throwable exception) {
        runtimeDisabled = true;
        if (reflectionFailureLogged) {
            return;
        }
        reflectionFailureLogged = true;
        MyMod.LOG.warn("[Compat][LootGames] Reflection invocation failed, disable minesweeper adapter call path", exception);
    }

    private static final class MasterTileResolution {

        private final Object masterTile;
        private final ChainTarget masterTarget;

        private MasterTileResolution(Object masterTile, ChainTarget masterTarget) {
            this.masterTile = masterTile;
            this.masterTarget = masterTarget;
        }
    }
}
