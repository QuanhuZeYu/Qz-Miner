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
     * 使用公开连接判定读取当前线缆已连接方向快照。
     *
     * @param tileEntity 线缆 TileEntity
     * @return 当前连接方向快照
     */
    List<ForgeDirection> captureConnectedSides(TileEntity tileEntity);

    /**
     * 替换线缆但暂不恢复连接状态。
     *
     * @param player 执行替换的玩家
     * @param tileEntity 原线缆 TileEntity
     * @param replacementStack 替换用物品栈
     * @param replacementSlotIndex 替换物品所在背包槽位
     * @param protectedMainHandSlot 受保护的主手槽位（返还旧线缆时跳过；-1 表示不保护）
     * @return 是否替换成功
     */
    boolean replaceCableWithoutConnections(EntityPlayerMP player, TileEntity tileEntity, ItemStack replacementStack, int replacementSlotIndex, int protectedMainHandSlot);

    /**
     * 按给定方向快照恢复线缆连接。
     *
     * @param tileEntity 已替换完成的新线缆 TileEntity
     * @param connectedSides 旧连接方向快照
     * @return 是否至少成功恢复一个连接
     */
    boolean reconnectCableSides(TileEntity tileEntity, List<ForgeDirection> connectedSides);
}
