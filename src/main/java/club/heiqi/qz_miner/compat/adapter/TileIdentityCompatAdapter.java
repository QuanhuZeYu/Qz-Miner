package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.tileentity.TileEntity;

/**
 * TileEntity 纯值身份捕获适配器。
 */
public interface TileIdentityCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断当前适配器是否能处理该 TileEntity。
     *
     * @param tileEntity 待捕获 TileEntity
     * @return 是否可处理
     */
    boolean supports(TileEntity tileEntity);

    /**
     * 捕获不可变纯值身份。
     *
     * <p>已识别类型的任何成员缺失、读取异常或值类型错误都必须返回 UNRESOLVED，
     * 禁止由门面继续降级到 runtime class。</p>
     *
     * @param tileEntity 待捕获 TileEntity
     * @return 身份令牌
     */
    TileIdentityToken capture(TileEntity tileEntity);
}
