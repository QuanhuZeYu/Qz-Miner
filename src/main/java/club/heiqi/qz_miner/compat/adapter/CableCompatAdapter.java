package club.heiqi.qz_miner.compat.adapter;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 线缆兼容适配器。
 *
 * 只暴露 Minecraft/Forge 基础类型，避免核心逻辑直接依赖具体线缆模组 API。
 */
public interface CableCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断 TileEntity 是否为可处理的线缆。
     *
     * @param tileEntity 待判断 TileEntity
     * @return 是否为线缆
     */
    boolean isCable(TileEntity tileEntity);

    /**
     * 读取线缆元方块 ID。
     *
     * @param tileEntity 线缆 TileEntity
     * @return 元方块 ID，不可识别时返回 -1
     */
    int getCableMetaTileId(TileEntity tileEntity);

    /**
     * 获取当前线缆已连接方向。
     *
     * @param tileEntity 线缆 TileEntity
     * @return 已连接方向
     */
    List<ForgeDirection> getConnectedSides(TileEntity tileEntity);

    /**
     * 判断物品栈是否为可替换线缆。
     *
     * @param stack 待判断物品栈
     * @return 是否为线缆物品
     */
    boolean isCableStack(ItemStack stack);

    /**
     * 替换线缆并尽量保留连接状态。
     *
     * @param player 执行替换的玩家
     * @param tileEntity 原线缆 TileEntity
     * @param replacementStack 替换用物品栈
     * @param replacementSlotIndex 替换物品所在背包槽位
     * @return 是否替换成功
     */
    boolean replaceCableKeepingConnections(EntityPlayerMP player, TileEntity tileEntity, ItemStack replacementStack, int replacementSlotIndex);
}
