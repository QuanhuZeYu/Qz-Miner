package club.heiqi.qz_miner.compat.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.gregtech.GregTechCableCompatAdapter;
import club.heiqi.qz_miner.compat.adapter.lootgames.LootGamesMinesweeperCompatAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.tileentity.TileEntity;

/**
 * 可选模组兼容适配器门面。
 */
public final class CompatAdapters {

    private static final CableCompatAdapter CABLE_ADAPTER = createCableAdapter();
    private static final MinesweeperCompatAdapter MINESWEEPER_ADAPTER = createMinesweeperAdapter();
    private static final List<OreCompatAdapter> ORE_ADAPTERS = createOreAdapters();
    private static final List<CropCompatAdapter> CROP_ADAPTERS = createCropAdapters();
    private static final List<TileIdentityCompatAdapter> TILE_IDENTITY_ADAPTERS = createTileIdentityAdapters();

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
            if (adapter.isCropBlock(block, tileEntity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断两个 TileEntity 是否可视为同类。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 目标 TileEntity
     * @return 是否可视为同类
     */
    public static boolean matchesTileEntity(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        if (sampleTileEntity == null && targetTileEntity == null) {
            return true;
        }

        if (sampleTileEntity == null || targetTileEntity == null) {
            return false;
        }

        for (TileIdentityCompatAdapter adapter : TILE_IDENTITY_ADAPTERS) {
            if (adapter.supports(sampleTileEntity, targetTileEntity)) {
                return adapter.matches(sampleTileEntity, targetTileEntity);
            }
        }
        return sampleTileEntity.getClass() == targetTileEntity.getClass();
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
        addCropAdapterIfAvailable(adapters, new NamedClassCropCompatAdapter(null, "ic2.core.crop.TileEntityCrop"));
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
