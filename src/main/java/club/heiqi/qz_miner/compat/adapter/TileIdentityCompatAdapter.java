package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.tileentity.TileEntity;

/**
 * TileEntity 同类判定适配器。
 */
public interface TileIdentityCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断当前适配器是否能处理这一组 TileEntity。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 目标 TileEntity
     * @return 是否可处理
     */
    boolean supports(TileEntity sampleTileEntity, TileEntity targetTileEntity);

    /**
     * 判断两个 TileEntity 是否可视为同类。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 目标 TileEntity
     * @return 是否视为同类
     */
    boolean matches(TileEntity sampleTileEntity, TileEntity targetTileEntity);
}
