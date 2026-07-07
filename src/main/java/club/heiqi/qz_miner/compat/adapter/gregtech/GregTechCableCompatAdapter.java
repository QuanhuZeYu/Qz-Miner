package club.heiqi.qz_miner.compat.adapter.gregtech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.compat.adapter.CableCompatAdapter;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTECable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * GregTech 线缆兼容适配器。
 */
public final class GregTechCableCompatAdapter implements CableCompatAdapter {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isCable(TileEntity tileEntity) {
        return getCable(tileEntity) != null;
    }

    @Override
    public int getCableMetaTileId(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return -1;
        }
        return gregTechTileEntity.getMetaTileID();
    }

    @Override
    public List<ForgeDirection> getConnectedSides(TileEntity tileEntity) {
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (baseMetaPipeEntity == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> connectedSides = new ArrayList<ForgeDirection>();
        byte connections = baseMetaPipeEntity.getConnections();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if ((connections & side.flag) != 0) {
                connectedSides.add(side);
            }
        }
        return connectedSides;
    }

    @Override
    public List<ForgeDirection> captureConnectedSides(TileEntity tileEntity) {
        MTECable cable = getCable(tileEntity);
        if (cable == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> connectedSides = new ArrayList<ForgeDirection>();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (cable.isConnectedAtSide(side)) {
                connectedSides.add(side);
            }
        }
        return connectedSides;
    }

    @Override
    public boolean isCableStack(ItemStack stack) {
        return createCableFromStack(stack) != null;
    }

    /**
     * 单阶段原子替换 GT 线缆 meta，并直写连接位掩码恢复连接状态。
     *
     * <p>实现要点（对齐 NORTH_STAR 信条五/六：显式降级）：
     * <ul>
     *   <li>不调用 {@code connect}/{@code disconnect} 状态机——这两个高级封装会解引用
     *       {@code getBaseMetaTileEntity()}，而新 meta 出厂 {@code mBaseMetaTileEntity} 恒为 null，
     *       会触发 NPE（原崩溃根因）；改用直写 {@code mConnections} 位掩码恢复连接。</li>
     *   <li>用 {@link MTECable#setBaseMetaTileEntity(IGregTechTileEntity)} 一次调用同时建立
     *       meta→base 与 base→meta 双向绑定，无邻居遍历、无网络副作用；
     *       旧实现只单向 {@code base.setMetaTileEntity(meta)}，导致 {@code hasValidMetaTileEntity()} 恒 false。</li>
     *   <li>连接网络图由 {@link GregTechAPI#causeCableUpdate} 重建，不依赖 connect/disconnect 回调。</li>
     *   <li>mutate 段包在 try 内，捕获 {@link RuntimeException} 精确回滚到旧 cable，不留半成品。</li>
     * </ul>
     *
     * @param player               执行玩家（主线程）
     * @param tileEntity           目标管线基座 {@link BaseMetaPipeEntity}
     * @param replacementStack     替换用线缆物品
     * @param replacementSlotIndex 替换物品所在槽位
     * @param protectedMainHandSlot 受保护的主手槽位（返还旧线缆时跳过；-1 表示不保护）
     * @return 替换成功返回 true；前置校验失败/异种判定不通过/异常回滚均返回 false
     */
    @Override
    public boolean replaceCableWithoutConnections(EntityPlayerMP player, TileEntity tileEntity, ItemStack replacementStack, int replacementSlotIndex, int protectedMainHandSlot) {
        // 前置校验：玩家、管线基座、替换物品任一为空即拒绝
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (player == null || baseMetaPipeEntity == null || replacementStack == null) {
            return false;
        }

        // 捕获旧态：连接位掩码（public byte，server 权威源）、旧 meta、旧 mID（用于回滚）
        byte oldConnections = baseMetaPipeEntity.getConnections();
        MTECable oldCable = getCable(baseMetaPipeEntity);
        MTECable handCable = createCableFromStack(replacementStack);
        if (oldCable == null || handCable == null) {
            return false;
        }
        short oldMetaId = (short) baseMetaPipeEntity.getMetaTileID();
        short newMetaId = (short) replacementStack.getItemDamage();

        // 异种判定：同类同材质同电压同电流则无需替换
        if (isSameCableType(oldCable, handCable)) {
            return false;
        }

        // 新建 meta：出厂 mBaseMetaTileEntity=null、mConnections=0
        IMetaTileEntity newMetaTileEntity = handCable.newMetaEntity(baseMetaPipeEntity);
        if (!(newMetaTileEntity instanceof MTECable newCable)) {
            return false;
        }

        // 关键 mutate 段：建立双向绑定 + 设新 mID + 直写连接位掩码，包在 try 内可回滚
        try {
            // 一次调用同时设 meta→base 与 base→meta，无邻居遍历、无网络副作用
            newCable.setBaseMetaTileEntity(baseMetaPipeEntity);
            // base 端记录新 mID，客户端 description packet 会带新 mID 重建 meta 并刷新纹理
            baseMetaPipeEntity.setMetaTileID(newMetaId);
            // 直写位掩码恢复连接，不走 connect/disconnect 状态机（避免 NPE 与回调副作用）
            newCable.mConnections = oldConnections;
            baseMetaPipeEntity.mConnections = oldConnections;
            // 刷新纹理/方块/tile 更新并触发 causeCableUpdate 重建网络图
            refreshPipe(baseMetaPipeEntity);
        } catch (RuntimeException e) {
            // 显式降级：回滚到旧 cable，不留半成品（对齐 NORTH_STAR 信条五/六）
            MyMod.LOG.warn(
                "[CableCompat] GT 线缆单阶段替换失败，已回滚 player={} pos=({},{},{}) oldMetaId={} newMetaId={} oldConnections={}",
                player.getCommandSenderName(),
                baseMetaPipeEntity.xCoord,
                baseMetaPipeEntity.yCoord,
                baseMetaPipeEntity.zCoord,
                oldMetaId,
                newMetaId,
                oldConnections,
                e);
            oldCable.setBaseMetaTileEntity(baseMetaPipeEntity);
            baseMetaPipeEntity.setMetaTileID(oldMetaId);
            oldCable.mConnections = oldConnections;
            baseMetaPipeEntity.mConnections = oldConnections;
            refreshPipe(baseMetaPipeEntity);
            return false;
        }

        // 成功后才消耗替换物品
        consumeReplacementStack(player, replacementStack, replacementSlotIndex, oldMetaId, protectedMainHandSlot);
        return true;
    }

    /**
     * 判定两根 GT 线缆是否属于同一种类型（同类同材质同电压同电流）。
     * 同种类型无需替换。
     *
     * @param a 线缆 A
     * @param b 线缆 B
     * @return 同种返回 true
     */
    private boolean isSameCableType(MTECable a, MTECable b) {
        return a.getClass() == b.getClass()
            && a.mMaterial == b.mMaterial
            && a.mVoltage == b.mVoltage
            && a.mAmperage == b.mAmperage;
    }

    @Override
    public boolean reconnectCableSides(TileEntity tileEntity, List<ForgeDirection> connectedSides) {
        MTECable cable = getCable(tileEntity);
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (cable == null || baseMetaPipeEntity == null || connectedSides == null || connectedSides.isEmpty()) {
            return false;
        }

        boolean connected = false;
        for (ForgeDirection side : connectedSides) {
            if (side == null || side == ForgeDirection.UNKNOWN) {
                continue;
            }

            if (cable.connect(side) > 0) {
                connected = true;
            }
        }

        baseMetaPipeEntity.markDirty();
        refreshPipe(baseMetaPipeEntity);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighborTileEntity = baseMetaPipeEntity.getTileEntityAtSide(side);
            if (neighborTileEntity instanceof BaseMetaPipeEntity neighborPipeEntity) {
                refreshPipe(neighborPipeEntity);
            }
        }

        return connected;
    }

    /**
     * 刷新 GT 管线的客户端显示、邻居更新和网络图状态。
     *
     * @param pipeEntity 管线实体
     */
    private void refreshPipe(BaseMetaPipeEntity pipeEntity) {
        pipeEntity.issueTextureUpdate();
        pipeEntity.issueBlockUpdate();
        pipeEntity.issueTileUpdate();
        GregTechAPI.causeCableUpdate(pipeEntity.getWorld(), pipeEntity.xCoord, pipeEntity.yCoord, pipeEntity.zCoord);
    }

    private MTECable getCable(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return null;
        }

        IMetaTileEntity metaTileEntity = gregTechTileEntity.getMetaTileEntity();
        if (!(metaTileEntity instanceof MTECable cable)) {
            return null;
        }
        return cable;
    }

    private BaseMetaPipeEntity getCableBase(TileEntity tileEntity) {
        return tileEntity instanceof BaseMetaPipeEntity baseMetaPipeEntity && isCable(tileEntity)
            ? baseMetaPipeEntity
            : null;
    }

    private MTECable createCableFromStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }

        Item machineItem = Item.getItemFromBlock(GregTechAPI.sBlockMachines);
        if (machineItem == null || stack.getItem() != machineItem) {
            return null;
        }
        int metaTileId = stack.getItemDamage();
        if (metaTileId < 0 || metaTileId >= GregTechAPI.METATILEENTITIES.length) {
            return null;
        }
        IMetaTileEntity metaTileEntity = GregTechAPI.METATILEENTITIES[metaTileId];
        return metaTileEntity instanceof MTECable cable ? cable : null;
    }

    private void consumeReplacementStack(EntityPlayerMP player, ItemStack replacementStack, int replacementSlotIndex, short oldMetaId, int protectedMainHandSlot) {
        if (player.capabilities.isCreativeMode) {
            return;
        }

        ItemStack oldCableStack = new ItemStack(replacementStack.getItem(), 1, oldMetaId);
        // 三级降级：并入已有堆叠（跳过主手）→ 空槽（跳过主手）→ 掉地兜底
        boolean addedToInventory = addOldCableToExistingStack(player, oldCableStack, protectedMainHandSlot);
        if (!addedToInventory) {
            addedToInventory = addOldCableToEmptySlot(player, oldCableStack, protectedMainHandSlot);
        }
        if (!addedToInventory) {
            player.dropPlayerItemWithRandomChoice(oldCableStack, false);
        }

        replacementStack.stackSize--;
        if (replacementStack.stackSize <= 0) {
            player.inventory.setInventorySlotContents(replacementSlotIndex, null);
        }
    }

    private boolean addOldCableToExistingStack(EntityPlayerMP player, ItemStack oldCableStack, int protectedMainHandSlot) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (i == protectedMainHandSlot) continue;
            ItemStack slot = player.inventory.mainInventory[i];
            if (slot != null
                && slot.getItem() == oldCableStack.getItem()
                && slot.getItemDamage() == oldCableStack.getItemDamage()
                && slot.stackSize < slot.getMaxStackSize()) {
                slot.stackSize++;
                // P2-2：并入堆叠后走 markDirty 通知背包同步（InventoryPlayer 实现 IInventory，markDirty 可用）
                player.inventory.markDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * 将旧线缆放入背包空槽（跳过主手 slot）。
     * 主手 slot 受会话锁保护，避免旧线缆占用主手导致类型锚点错乱。
     */
    private boolean addOldCableToEmptySlot(EntityPlayerMP player, ItemStack oldCableStack, int protectedMainHandSlot) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (i == protectedMainHandSlot) continue;
            if (player.inventory.mainInventory[i] == null) {
                // P2-1：走 setInventorySlotContents 而非直写数组，触发 markDirty（InventoryPlayer 实现该方法）
                player.inventory.setInventorySlotContents(i, oldCableStack);
                return true;
            }
        }
        return false;
    }
}
