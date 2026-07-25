package club.heiqi.qz_miner.compat.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.gregtech.GregTechCableCompatAdapter;
import club.heiqi.qz_miner.compat.adapter.lootgames.LootGamesMinesweeperCompatAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.IGrowable;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 可选模组兼容适配器门面。
 */
public final class CompatAdapters {

    private static final CableCompatAdapter CABLE_ADAPTER = createCableAdapter();
    private static final MinesweeperCompatAdapter MINESWEEPER_ADAPTER = createMinesweeperAdapter();
    private static final List<OreCompatAdapter> ORE_ADAPTERS = createOreAdapters();
    private static final List<CropCompatAdapter> CROP_ADAPTERS = createCropAdapters();
    private static final List<TileIdentityCompatAdapter> TILE_IDENTITY_ADAPTERS = createTileIdentityAdapters();
    private static final List<ToolHarvestCompatAdapter> TOOL_HARVEST_ADAPTERS = createToolHarvestAdapters();

    private CompatAdapters() {}

    /**
     * 获取线缆适配器。
     *
     * @return 线缆适配器
     */
    public static CableCompatAdapter cable() {
        return CABLE_ADAPTER;
    }

    /**
     * 获取扫雷适配器。
     *
     * @return 扫雷适配器
     */
    public static MinesweeperCompatAdapter minesweeper() {
        return MINESWEEPER_ADAPTER;
    }

    /**
     * 判断主模式当前是否可用。
     *
     * @param mode 主模式
     * @return 是否可用
     */
    public static boolean isModeAvailable(ChainMode mode) {
        return mode != ChainMode.SPECIAL
            || isSubModeAvailable(ChainSubMode.SPECIAL_GT_CABLE_REPLACE)
            || isSubModeAvailable(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER);
    }

    /**
     * 判断子模式当前是否可用。
     *
     * @param subMode 子模式
     * @return 是否可用
     */
    public static boolean isSubModeAvailable(ChainSubMode subMode) {
        if (subMode == ChainSubMode.SPECIAL_GT_CABLE_REPLACE) {
            return CABLE_ADAPTER.isAvailable();
        }
        if (subMode == ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER) {
            return MINESWEEPER_ADAPTER.isAvailable();
        }
        return true;
    }

    /**
     * 判断方块或 TileEntity 是否视为矿石。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为矿石
     */
    public static boolean isOreBlock(Block block, TileEntity tileEntity) {
        if (block == null) {
            return false;
        }

        if (block instanceof BlockOre || block instanceof BlockRedstoneOre) {
            return true;
        }

        for (OreCompatAdapter adapter : ORE_ADAPTERS) {
            if (adapter.isOreBlock(block, tileEntity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断方块或 TileEntity 是否视为作物。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为作物
     */
    public static boolean isCropBlock(Block block, TileEntity tileEntity) {
        if (block == null) {
            return false;
        }

        if (block instanceof BlockCrops) {
            return true;
        }

        for (CropCompatAdapter adapter : CROP_ADAPTERS) {
            try {
                if (adapter != null && adapter.isAvailable() && adapter.isCropBlock(block, tileEntity)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }
        return false;
    }

    /**
     * 查询当前 live 方块的可靠作物生长状态。
     *
     * <p>方块/metadata 已漂移、可选兼容异常或任何未知结果都返回 UNKNOWN。</p>
     *
     * @param world 当前只读世界
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @param block 调用方刚读取的方块
     * @param metadata 调用方刚读取的完整 metadata
     * @param tileEntity 调用方刚读取的 TileEntity，可为 null
     * @return 三态生长结果
     */
    public static CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        if (!matchesLiveBlock(world, x, y, z, block, metadata)) {
            return CropGrowthState.UNKNOWN;
        }

        if (block instanceof BlockCrops) {
            return vanillaCropGrowthState(world, x, y, z, block);
        }
        return growthState(world, x, y, z, block, metadata, tileEntity, CROP_ADAPTERS);
    }

    /** 包级适配器矩阵接缝，验证首个已知结果与异常 fail-closed。 */
    static CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity, List<CropCompatAdapter> adapters) {
        if (block == null || adapters == null) {
            return CropGrowthState.UNKNOWN;
        }
        for (CropCompatAdapter adapter : adapters) {
            if (adapter == null) {
                return CropGrowthState.UNKNOWN;
            }
            try {
                if (!adapter.isAvailable() || !adapter.isCropBlock(block, tileEntity)) {
                    continue;
                }
                CropGrowthState state = adapter.growthState(
                        world, x, y, z, block, metadata, tileEntity);
                if (state == null) {
                    return CropGrowthState.UNKNOWN;
                }
                if (state != CropGrowthState.UNKNOWN) {
                    return state;
                }
            } catch (RuntimeException | LinkageError failure) {
                return CropGrowthState.UNKNOWN;
            }
        }
        return CropGrowthState.UNKNOWN;
    }

    /** 包级纯值映射接缝，固定 vanilla can-grow 的三态语义。 */
    static CropGrowthState classifyVanillaGrowth(boolean canContinueGrowing) {
        return canContinueGrowing ? CropGrowthState.IMMATURE : CropGrowthState.MATURE;
    }

    private static boolean matchesLiveBlock(World world, int x, int y, int z, Block block, int metadata) {
        if (world == null || block == null || metadata < 0) {
            return false;
        }
        try {
            return world.getBlock(x, y, z) == block && world.getBlockMetadata(x, y, z) == metadata;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static CropGrowthState vanillaCropGrowthState(World world, int x, int y, int z, Block block) {
        if (!(block instanceof IGrowable)) {
            return CropGrowthState.UNKNOWN;
        }
        try {
            boolean canContinueGrowing = ((IGrowable) block).func_149851_a(world, x, y, z, world.isRemote);
            return classifyVanillaGrowth(canContinueGrowing);
        } catch (RuntimeException | LinkageError failure) {
            return CropGrowthState.UNKNOWN;
        }
    }

    /**
     * 在当前线程立即捕获 TileEntity 的不可变纯值身份。
     *
     * <p>已识别适配器一旦选中，任何读取失败都保留 UNRESOLVED，禁止降级 runtime class。
     * 未被适配器识别的 TileEntity 沿既有运行时类语义，以类型名纯值表示。</p>
     *
     * @param tileEntity 当前线程读取到的 TileEntity，可为 null
     * @return 不可变身份令牌
     */
    public static TileIdentityToken captureTileIdentity(TileEntity tileEntity) {
        return captureTileIdentity(tileEntity, TILE_IDENTITY_ADAPTERS);
    }

    /** 包级适配器矩阵接缝，供纯 JVM 测试验证“识别失败不降级”。 */
    static TileIdentityToken captureTileIdentity(TileEntity tileEntity,
            List<TileIdentityCompatAdapter> adapters) {
        if (tileEntity == null) {
            return TileIdentityToken.absent();
        }

        if (adapters != null) {
            for (TileIdentityCompatAdapter adapter : adapters) {
                if (adapter == null || !adapter.isAvailable()) {
                    continue;
                }
                try {
                    if (adapter.supports(tileEntity)) {
                        TileIdentityToken token = adapter.capture(tileEntity);
                        return token == null ? TileIdentityToken.unresolved() : token;
                    }
                } catch (RuntimeException | LinkageError failure) {
                    return TileIdentityToken.unresolved();
                }
            }
        }

        try {
            String runtimeTypeName = tileEntity.getClass().getName();
            return TileIdentityToken.present("runtime-class", runtimeTypeName, "same-runtime-type");
        } catch (RuntimeException | LinkageError failure) {
            return TileIdentityToken.unresolved();
        }
    }

    /**
     * 按默认矩阵比较两个纯值身份令牌。
     *
     * @param sampleIdentity 起点身份
     * @param targetIdentity 候选身份
     * @return 是否身份等价
     */
    public static boolean matchesTileIdentity(TileIdentityToken sampleIdentity, TileIdentityToken targetIdentity) {
        if (sampleIdentity == null || targetIdentity == null
                || !sampleIdentity.isResolved() || !targetIdentity.isResolved()) {
            return false;
        }
        if (sampleIdentity.getState() == TileIdentityToken.State.ABSENT
                || targetIdentity.getState() == TileIdentityToken.State.ABSENT) {
            return sampleIdentity.getState() == TileIdentityToken.State.ABSENT
                    && targetIdentity.getState() == TileIdentityToken.State.ABSENT;
        }
        return sampleIdentity.equals(targetIdentity);
    }

    /**
     * 既有 live TileEntity 比较兼容壳；内部立即转纯值 token，不保留对象。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 候选 TileEntity
     * @return 是否身份等价
     */
    public static boolean matchesTileEntity(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        return matchesTileIdentity(captureTileIdentity(sampleTileEntity), captureTileIdentity(targetTileEntity));
    }

    /**
     * 对无显式 harvestTool 的真实工具执行可选兼容采掘求值。
     *
     * @param item 栈持有的 Item
     * @param stack 当前工具栈
     * @param target 目标方块
     * @return 首个适用适配器的四态终裁
     */
    public static ToolHarvestCompatAdapter.Result evaluateToolHarvest(Item item, ItemStack stack, Block target) {
        return evaluateToolHarvest(item, stack, target, TOOL_HARVEST_ADAPTERS);
    }

    /** 包级适配器矩阵接缝，验证首个适用结果终裁与异常 fail-closed。 */
    static ToolHarvestCompatAdapter.Result evaluateToolHarvest(Item item, ItemStack stack, Block target,
            List<ToolHarvestCompatAdapter> adapters) {
        if (item == null || stack == null || target == null || adapters == null) {
            return ToolHarvestCompatAdapter.Result.UNRESOLVED;
        }
        for (ToolHarvestCompatAdapter adapter : adapters) {
            if (adapter == null) {
                return ToolHarvestCompatAdapter.Result.UNRESOLVED;
            }
            final ToolHarvestCompatAdapter.Result result;
            try {
                result = adapter.evaluate(item, stack, target);
            } catch (RuntimeException | LinkageError failure) {
                return ToolHarvestCompatAdapter.Result.UNRESOLVED;
            }
            if (result == null) {
                return ToolHarvestCompatAdapter.Result.UNRESOLVED;
            }
            if (result != ToolHarvestCompatAdapter.Result.NOT_APPLICABLE) {
                return result;
            }
        }
        return ToolHarvestCompatAdapter.Result.NOT_APPLICABLE;
    }

    private static CableCompatAdapter createCableAdapter() {
        if (ClassNameCompatSupport.isClassPresent("gregtech.api.interfaces.tileentity.IGregTechTileEntity")
            && ClassNameCompatSupport.isClassPresent("gregtech.api.metatileentity.implementations.MTECable")
            && ClassNameCompatSupport.isClassPresent("gregtech.api.metatileentity.BaseMetaPipeEntity")) {
            return new GregTechCableCompatAdapter();
        }
        return new NoOpCableCompatAdapter();
    }

    private static MinesweeperCompatAdapter createMinesweeperAdapter() {
        if (ClassNameCompatSupport.isClassPresent("ru.timeconqueror.lootgames.common.block.tile.MSMasterTile")
            && ClassNameCompatSupport.isClassPresent("ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper")) {
            MinesweeperCompatAdapter adapter = new LootGamesMinesweeperCompatAdapter();
            if (adapter.isAvailable()) {
                return adapter;
            }
        }
        return new NoOpMinesweeperCompatAdapter();
    }

    private static List<OreCompatAdapter> createOreAdapters() {
        List<OreCompatAdapter> adapters = new ArrayList<OreCompatAdapter>();
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("gregtech.common.blocks.BlockOresAbstract", "gregtech.common.blocks.TileEntityOres"));
        // GTNH 2.9 矿石重构（GT5-Unofficial commit 20931d3b, 2025-10）后：
        // 新世界矿石方块改用 GTBlockOre（继承 GTGenericBlock，无 TileEntity，meta+OreInfo 编码）；
        // 旧存档矿石仍为 BlockOresAbstractLegacy（原 BlockOresAbstract 改名）+ TileEntityOres（仅保留数据字段）。
        // 二者均需注册，覆盖新存档与旧存档迁移前的矿石。
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("gregtech.common.blocks.GTBlockOre", null));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("gregtech.common.blocks.BlockOresAbstractLegacy", "gregtech.common.blocks.TileEntityOres"));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("gregtech.common.blocks.BlockOresLegacy", "gregtech.common.blocks.TileEntityOres"));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("bartworks.system.material.BWMetaGeneratedSmallOres", null));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("bartworks.system.material.BWMetaGeneratedOres", null));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter(null, "bartworks.system.material.BWTileEntityMetaGeneratedOre"));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter(null, "bartworks.system.material.BWTileEntityMetaGeneratedSmallOre"));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("gtPlusPlus.core.block.base.BlockBaseOre", null));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("appeng.block.solids.OreQuartz", null));
        addOreAdapterIfAvailable(adapters, new NamedClassOreCompatAdapter("appeng.block.solids.OreQuartzCharged", null));
        addOreAdapterIfAvailable(adapters, new EtFuturumOreCompatAdapter());
        return Collections.unmodifiableList(adapters);
    }

    private static List<CropCompatAdapter> createCropAdapters() {
        List<CropCompatAdapter> adapters = new ArrayList<CropCompatAdapter>();
        addCropAdapterIfAvailable(adapters, new Ic2CropCompatAdapter());
        addCropAdapterIfAvailable(adapters, new EtFuturumCropCompatAdapter());
        return Collections.unmodifiableList(adapters);
    }

    private static List<TileIdentityCompatAdapter> createTileIdentityAdapters() {
        List<TileIdentityCompatAdapter> adapters = new ArrayList<TileIdentityCompatAdapter>();
        addTileIdentityAdapterIfAvailable(adapters, new ReflectiveGregTechTileIdentityCompatAdapter());
        addTileIdentityAdapterIfAvailable(adapters, new ReflectiveFieldTileIdentityCompatAdapter("gregtech.common.blocks.TileEntityOres", "mMetaData"));
        addTileIdentityAdapterIfAvailable(adapters, new ReflectiveFieldTileIdentityCompatAdapter("bartworks.system.material.TileEntityMetaGeneratedBlock", "mMetaData"));
        return Collections.unmodifiableList(adapters);
    }

    private static List<ToolHarvestCompatAdapter> createToolHarvestAdapters() {
        return Collections.<ToolHarvestCompatAdapter>singletonList(new TConstructToolHarvestCompatAdapter());
    }

    private static void addOreAdapterIfAvailable(List<OreCompatAdapter> adapters, OreCompatAdapter adapter) {
        if (adapter != null && adapter.isAvailable()) {
            adapters.add(adapter);
        }
    }

    private static void addCropAdapterIfAvailable(List<CropCompatAdapter> adapters, CropCompatAdapter adapter) {
        if (adapter != null && adapter.isAvailable()) {
            adapters.add(adapter);
        }
    }

    private static void addTileIdentityAdapterIfAvailable(List<TileIdentityCompatAdapter> adapters, TileIdentityCompatAdapter adapter) {
        if (adapter != null && adapter.isAvailable()) {
            adapters.add(adapter);
        }
    }
}
