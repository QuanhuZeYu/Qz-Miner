package club.heiqi.qz_miner.compat.adapter.lootgames;

import java.lang.reflect.Constructor;
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
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * LootGames 扫雷兼容适配器。
 */
public final class LootGamesMinesweeperCompatAdapter implements MinesweeperCompatAdapter {

    private final Class<?> masterTileType;
    private final Class<?> smartSubordinateBlockType;
    private final Class<?> boardBorderBlockType;
    private final Constructor<?> pos2iConstructor;
    private final Method masterTileGetGameMethod;
    private final Method gameIsBoardGeneratedMethod;
    private final Method gameGetBoardMethod;
    private final Method boardSizeMethod;
    private final Method boardGetTypeMethod;
    private final Method gameConvertToBlockPosMethod;
    private final Method blockPosOfMethod;
    private final Method blockPosGetXMethod;
    private final Method blockPosGetYMethod;
    private final Method blockPosGetZMethod;
    private final Method blockStateOfMethod;
    private final Method smartSubordinateGetMasterPosMethod;
    private final Method boardBorderGetMasterPosMethod;
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
        Class<?> resolvedTypeEnumType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.minigame.minesweeper.Type");
        Class<?> resolvedBlockPosType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.utils.future.BlockPos");
        Class<?> resolvedBlockStateType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.utils.future.BlockState");
        Class<?> resolvedSmartSubordinateBlockType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock");
        Class<?> resolvedBoardBorderBlockType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.block.BoardBorderBlock");
        Class<?> resolvedPos2iType = ClassNameCompatSupport.resolveClass("ru.timeconqueror.lootgames.api.util.Pos2i");

        this.masterTileType = resolvedMasterTileType;
        this.smartSubordinateBlockType = resolvedSmartSubordinateBlockType;
        this.boardBorderBlockType = resolvedBoardBorderBlockType;
        this.pos2iConstructor = resolveConstructor(resolvedPos2iType, int.class, int.class);
        this.masterTileGetGameMethod = resolveMethod(resolvedMasterTileType, "getGame");
        this.gameIsBoardGeneratedMethod = resolveMethod(resolvedGameMineSweeperType, "isBoardGenerated");
        this.gameGetBoardMethod = resolveMethod(resolvedGameMineSweeperType, "getBoard");
        this.boardSizeMethod = resolveMethod(resolvedBoardType, "size");
        this.boardGetTypeMethod = resolveMethod(resolvedBoardType, "getType", int.class, int.class);
        this.gameConvertToBlockPosMethod = resolveMethod(resolvedGameMineSweeperType, "convertToBlockPos", resolvedPos2iType);
        this.blockPosOfMethod = resolveMethod(resolvedBlockPosType, "of", int.class, int.class, int.class);
        this.blockPosGetXMethod = resolveMethod(resolvedBlockPosType, "getX");
        this.blockPosGetYMethod = resolveMethod(resolvedBlockPosType, "getY");
        this.blockPosGetZMethod = resolveMethod(resolvedBlockPosType, "getZ");
        this.blockStateOfMethod = resolveMethod(resolvedBlockStateType, "of", Block.class, int.class);
        this.smartSubordinateGetMasterPosMethod = resolveMethod(resolvedSmartSubordinateBlockType, "getMasterPos", World.class, resolvedBlockPosType);
        this.boardBorderGetMasterPosMethod = resolveMethod(resolvedBoardBorderBlockType, "getMasterPos", World.class, resolvedBlockPosType, resolvedBlockStateType);
        this.bombType = resolveStaticField(resolvedTypeEnumType, "BOMB");
        this.available = resolvedMasterTileType != null
            && resolvedGameMineSweeperType != null
            && resolvedBoardType != null
            && resolvedTypeEnumType != null
            && resolvedBlockPosType != null
            && resolvedBlockStateType != null
            && resolvedSmartSubordinateBlockType != null
            && resolvedBoardBorderBlockType != null
            && resolvedPos2iType != null
            && pos2iConstructor != null
            && masterTileGetGameMethod != null
            && gameIsBoardGeneratedMethod != null
            && gameGetBoardMethod != null
            && boardSizeMethod != null
            && boardGetTypeMethod != null
            && gameConvertToBlockPosMethod != null
            && blockPosOfMethod != null
            && blockPosGetXMethod != null
            && blockPosGetYMethod != null
            && blockPosGetZMethod != null
            && blockStateOfMethod != null
            && smartSubordinateGetMasterPosMethod != null
            && boardBorderGetMasterPosMethod != null
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
        Object masterTile = resolveMasterTile(world, target);
        if (masterTile == null) {
            return Collections.emptyList();
        }

        Object game = invoke(masterTileGetGameMethod, masterTile);
        if (game == null || !asBoolean(invoke(gameIsBoardGeneratedMethod, game))) {
            return Collections.emptyList();
        }

        Object board = invoke(gameGetBoardMethod, game);
        int boardSize = asInt(invoke(boardSizeMethod, board));
        if (board == null || boardSize <= 0) {
            return Collections.emptyList();
        }
        int cappedMaxTargets = Math.max(1, maxTargets);
        List<ChainTarget> result = new ArrayList<ChainTarget>(Math.min(boardSize * boardSize, cappedMaxTargets));

        for (int x = 0; x < boardSize && result.size() < cappedMaxTargets; x++) {
            for (int y = 0; y < boardSize && result.size() < cappedMaxTargets; y++) {
                Object cellType = invoke(boardGetTypeMethod, board, Integer.valueOf(x), Integer.valueOf(y));
                if (cellType == null || !bombType.equals(cellType)) {
                    continue;
                }

                Object boardPosition = newInstance(pos2iConstructor, Integer.valueOf(x), Integer.valueOf(y));
                Object bombPos = invoke(gameConvertToBlockPosMethod, game, boardPosition);
                if (!isWithinPreviewRadius(target, bombPos, radius)) {
                    continue;
                }

                result.add(new ChainTarget(
                    readCoordinate(bombPos, blockPosGetXMethod),
                    readCoordinate(bombPos, blockPosGetYMethod),
                    readCoordinate(bombPos, blockPosGetZMethod)));
            }
        }

        return result;
    }

    private boolean isWithinPreviewRadius(ChainTarget target, Object blockPos, int radius) {
        if (target == null || blockPos == null) {
            return false;
        }

        return Math.abs(readCoordinate(blockPos, blockPosGetXMethod) - target.getX()) <= radius
            && Math.abs(readCoordinate(blockPos, blockPosGetYMethod) - target.getY()) <= radius
            && Math.abs(readCoordinate(blockPos, blockPosGetZMethod) - target.getZ()) <= radius;
    }

    private Object resolveMasterTile(World world, ChainTarget target) {
        if (!isAvailable() || world == null || target == null) {
            return null;
        }

        TileEntity tileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (masterTileType.isInstance(tileEntity)) {
            return tileEntity;
        }

        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null) {
            return null;
        }

        Object pos = invoke(blockPosOfMethod, null, Integer.valueOf(target.getX()), Integer.valueOf(target.getY()), Integer.valueOf(target.getZ()));
        Object masterPos = null;
        if (smartSubordinateBlockType.isInstance(block)) {
            masterPos = invoke(smartSubordinateGetMasterPosMethod, null, world, pos);
        } else if (boardBorderBlockType.isInstance(block)) {
            int meta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
            Object blockState = invoke(blockStateOfMethod, null, block, Integer.valueOf(meta));
            masterPos = invoke(boardBorderGetMasterPosMethod, null, world, pos, blockState);
        }

        if (masterPos == null) {
            return null;
        }

        TileEntity masterTile = world.getTileEntity(
            readCoordinate(masterPos, blockPosGetXMethod),
            readCoordinate(masterPos, blockPosGetYMethod),
            readCoordinate(masterPos, blockPosGetZMethod));
        return masterTileType.isInstance(masterTile) ? masterTile : null;
    }

    private int readCoordinate(Object blockPos, Method coordinateGetter) {
        return asInt(invoke(coordinateGetter, blockPos));
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    private Constructor<?> resolveConstructor(Class<?> ownerType, Class<?>... parameterTypes) {
        if (ownerType == null) {
            return null;
        }

        try {
            return ownerType.getConstructor(parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Method resolveMethod(Class<?> ownerType, String methodName, Class<?>... parameterTypes) {
        if (ownerType == null || methodName == null || methodName.isEmpty()) {
            return null;
        }

        try {
            return ownerType.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Object resolveStaticField(Class<?> ownerType, String fieldName) {
        if (ownerType == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        try {
            Field field = ownerType.getField(fieldName);
            return field.get(null);
        } catch (IllegalAccessException | NoSuchFieldException ignored) {
            return null;
        }
    }

    private Object newInstance(Constructor<?> constructor, Object... arguments) {
        if (constructor == null) {
            return null;
        }

        try {
            return constructor.newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
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
        } catch (IllegalAccessException | InvocationTargetException e) {
            logReflectionFailure(e);
            return null;
        }
    }

    private void logReflectionFailure(Exception exception) {
        runtimeDisabled = true;
        if (reflectionFailureLogged) {
            return;
        }
        reflectionFailureLogged = true;
        MyMod.LOG.warn("[Compat][LootGames] Reflection invocation failed, disable minesweeper adapter call path", exception);
    }
}
