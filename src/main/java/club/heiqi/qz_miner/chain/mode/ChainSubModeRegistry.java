package club.heiqi.qz_miner.chain.mode;

import java.util.EnumMap;
import java.util.Map;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilter;
import club.heiqi.qz_miner.chain.planner.ChainResolverContext;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraverser;
import club.heiqi.qz_miner.network.PacketLootGamesMinesweeperPreviewRequest;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 连锁子模式注册表。
 */
public final class ChainSubModeRegistry {

    private static final Map<ChainSubMode, ChainSubModeDefinition> DEFINITIONS = new EnumMap<ChainSubMode, ChainSubModeDefinition>(ChainSubMode.class);

    private ChainSubModeRegistry() {}

    public static void clearDefinitions() {
        DEFINITIONS.clear();
    }

    public static void register(ChainSubModeDefinition definition) {
        if (definition == null || definition.getSubMode() == null) {
            MyMod.LOG.warn("[ChainSubModeRegistry] Ignore invalid sub mode definition: {}", definition);
            return;
        }

        if (DEFINITIONS.containsKey(definition.getSubMode())) {
            MyMod.LOG.warn("[ChainSubModeRegistry] Duplicate sub mode definition detected, overriding subMode={}", definition.getSubMode());
        }
        DEFINITIONS.put(definition.getSubMode(), definition);
    }

    public static ChainSubModeDefinition getDefinition(ChainSubMode subMode) {
        return subMode == null ? null : DEFINITIONS.get(subMode);
    }

    public static ChainSubModeTrigger getTrigger(ChainSubMode subMode) {
        ChainSubModeDefinition definition = getDefinition(subMode);
        return definition == null ? ChainSubModeTrigger.NONE : definition.getTrigger();
    }

    public static ChainTraverser createTraverser(ChainResolverContext context, ChainTraverser fallback) {
        ChainSubModeDefinition definition = getDefinition(context == null || context.getSearchContext() == null ? null : context.getSearchContext().getSubMode());
        return definition == null ? fallback : definition.resolveTraverser(context, fallback);
    }

    public static ChainBlockMatcher createMatcher(ChainResolverContext context, ChainBlockMatcher fallback) {
        ChainSubModeDefinition definition = getDefinition(context == null || context.getSearchContext() == null ? null : context.getSearchContext().getSubMode());
        return definition == null ? fallback : definition.resolveMatcher(context, fallback);
    }

    public static ChainCandidateFilter createCandidateFilter(ChainSearchContext context, ChainCandidateFilter fallback) {
        ChainSubModeDefinition definition = getDefinition(context == null ? null : context.getSubMode());
        return definition == null ? fallback : definition.resolveCandidateFilter(context, fallback);
    }

    public static int[] resolveAreaDimensions(int radius, ChainSubMode subMode, ChainAreaPresentationResolver fallback) {
        ChainSubModeDefinition definition = getDefinition(subMode);
        int[] dimensions = definition == null ? null : definition.resolveAreaDimensions(radius);
        if (dimensions != null) {
            return dimensions;
        }
        return fallback == null ? null : fallback.resolveDimensions(radius, subMode);
    }

    public static boolean canStartPreview(ChainSubMode subMode, World world, ChainTarget target, TileEntity sampleTileEntity) {
        ChainSubModeDefinition definition = getDefinition(subMode);
        return definition == null || definition.canPreview(world, target, sampleTileEntity);
    }

    public static boolean usesRemotePreview(ChainSubMode subMode) {
        ChainSubModeDefinition definition = getDefinition(subMode);
        return definition != null && definition.usesRemotePreview();
    }

    public static boolean requestRemotePreview(ChainSubMode subMode, int requestId, ChainTarget target, int radius, int maxTargets) {
        if (subMode != ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER || MyMod.networkMain == null || target == null) {
            return false;
        }

        MyMod.networkMain.network.sendToServer(new PacketLootGamesMinesweeperPreviewRequest(requestId, target, radius, maxTargets));
        return true;
    }

    public static ChainActionExecutor resolveActionExecutor(ChainSubMode subMode, ChainActionExecutor fallback) {
        ChainSubModeDefinition definition = getDefinition(subMode);
        return definition == null ? fallback : definition.resolveActionExecutor(fallback);
    }

    public static void validateDefinitions() {
        for (ChainSubMode subMode : ChainSubMode.values()) {
            if (!DEFINITIONS.containsKey(subMode)) {
                MyMod.LOG.warn("[ChainSubModeRegistry] Missing sub mode definition after bootstrap: subMode={}", subMode);
            }
        }
    }
}
