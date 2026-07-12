package club.heiqi.qz_miner.compat.adapter.gregtech;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.compat.adapter.CableCompatAdapter;
import club.heiqi.qz_miner.compat.adapter.CableReplacementResult;
import club.heiqi.qz_miner.compat.adapter.ClassNameCompatSupport;
import club.heiqi.qz_miner.compat.adapter.ReflectiveMemberSupport;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 通过完整反射能力档案访问 GregTech 线缆，不产生 GT 编译期静态引用。
 */
public final class GregTechCableCompatAdapter implements CableCompatAdapter {

    private final CapabilityProfile profile;

    /** 使用当前类加载器解析正式 GT 能力档案。 */
    public GregTechCableCompatAdapter() {
        this(CapabilityProfile.resolve(new ProductionClassResolver()));
    }

    GregTechCableCompatAdapter(CapabilityProfile profile) {
        this.profile = profile;
    }

    @Override
    public boolean isAvailable() {
        return profile != null && profile.complete;
    }

    @Override
    public boolean isCable(TileEntity tileEntity) {
        return getCable(tileEntity) != null;
    }

    @Override
    public int getCableMetaTileId(TileEntity tileEntity) {
        if (!isAvailable() || !profile.gregTechTileType.isInstance(tileEntity)) return -1;
        Object value = invoke(profile.getMetaTileId, tileEntity);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @Override
    public List<ForgeDirection> getConnectedSides(TileEntity tileEntity) {
        Object base = getCableBase(tileEntity);
        Object value = base == null ? null : invoke(profile.getConnections, base);
        return value instanceof Number ? sides(((Number) value).byteValue()) : Collections.<ForgeDirection>emptyList();
    }

    @Override
    public List<ForgeDirection> captureConnectedSides(TileEntity tileEntity) {
        Object cable = getCable(tileEntity);
        if (cable == null) return Collections.emptyList();
        List<ForgeDirection> result = new ArrayList<ForgeDirection>();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (Boolean.TRUE.equals(invoke(profile.isConnectedAtSide, cable, side))) result.add(side);
        }
        return result;
    }

    @Override
    public boolean isCableStack(ItemStack stack) {
        return createCableFromStack(stack) != null;
    }

    @Override
    public CableReplacementResult replaceCableWithoutConnections(EntityPlayerMP player, TileEntity tileEntity,
        ItemStack replacementStack, int replacementSlotIndex, int protectedMainHandSlot) {
        Object base = getCableBase(tileEntity);
        Object oldCable = getCable(tileEntity);
        Object handCable = createCableFromStack(replacementStack);
        if (player == null || base == null || oldCable == null || handCable == null) return CableReplacementResult.failure();

        byte oldConnections = byteValue(invoke(profile.getConnections, base));
        short oldMetaId = (short) getCableMetaTileId(tileEntity);
        short newMetaId = (short) replacementStack.getItemDamage();
        if (isSameCableType(oldCable, handCable)) return CableReplacementResult.failure();
        Object newCable = invoke(profile.newMetaEntity, handCable, base);
        if (!profile.metaTileType.isInstance(newCable) || !profile.cableType.isInstance(newCable)) {
            return CableReplacementResult.failure();
        }

        try {
            invokeRequired(profile.setBaseMetaTileEntity, newCable, base);
            invokeRequired(profile.setMetaTileId, base, Short.valueOf(newMetaId));
            profile.cableConnections.setByte(newCable, oldConnections);
            profile.baseConnections.setByte(base, oldConnections);
            refreshPipe(base, tileEntity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            rollback(player, tileEntity, base, oldCable, oldMetaId, oldConnections, newMetaId, e);
            return CableReplacementResult.failure();
        }

        ItemStack returned = player.capabilities.isCreativeMode
            ? null : new ItemStack(replacementStack.getItem(), 1, oldMetaId);
        if (!player.capabilities.isCreativeMode) {
            replacementStack.stackSize--;
            if (replacementStack.stackSize <= 0) player.inventory.setInventorySlotContents(replacementSlotIndex, null);
        }
        return CableReplacementResult.success(returned);
    }

    @Override
    public boolean reconnectCableSides(TileEntity tileEntity, List<ForgeDirection> connectedSides) {
        Object base = getCableBase(tileEntity);
        Object cable = getCable(tileEntity);
        if (base == null || cable == null || connectedSides == null || connectedSides.isEmpty()) return false;
        byte mask = 0;
        for (ForgeDirection side : connectedSides) {
            if (side != null && side != ForgeDirection.UNKNOWN) mask |= (byte) side.flag;
        }
        try {
            profile.cableConnections.setByte(cable, mask);
            profile.baseConnections.setByte(base, mask);
            refreshPipe(base, tileEntity);
            return mask != 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean isSameCableType(Object a, Object b) {
        try {
            return a.getClass() == b.getClass()
                && profile.material.get(a) == profile.material.get(b)
                && profile.cableLoss.getLong(a) == profile.cableLoss.getLong(b)
                && profile.amperage.getLong(a) == profile.amperage.getLong(b)
                && profile.voltage.getLong(a) == profile.voltage.getLong(b);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private Object getCable(TileEntity tileEntity) {
        if (!isAvailable() || !profile.gregTechTileType.isInstance(tileEntity)) return null;
        Object meta = invoke(profile.getMetaTileEntity, tileEntity);
        return profile.cableType.isInstance(meta) ? meta : null;
    }

    private Object getCableBase(TileEntity tileEntity) {
        return isAvailable() && profile.basePipeType.isInstance(tileEntity) && getCable(tileEntity) != null ? tileEntity : null;
    }

    private Object createCableFromStack(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.getItem() == null) return null;
        try {
            Object block = profile.blockMachines.get(null);
            if (!(block instanceof Block) || stack.getItem() != Item.getItemFromBlock((Block) block)) return null;
            Object entries = profile.metaTileEntities.get(null);
            int id = stack.getItemDamage();
            if (entries == null || id < 0 || id >= Array.getLength(entries)) return null;
            Object meta = Array.get(entries, id);
            return profile.cableType.isInstance(meta) ? meta : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private void refreshPipe(Object base, TileEntity tile) throws ReflectiveOperationException {
        invokeRequired(profile.issueTextureUpdate, base);
        invokeRequired(profile.issueBlockUpdate, base);
        invokeRequired(profile.issueTileUpdate, base);
        invokeRequired(profile.causeCableUpdate, null, tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
    }

    private void rollback(EntityPlayerMP player, TileEntity tile, Object base, Object oldCable, short oldId,
        byte oldConnections, short newId, Throwable failure) {
        MyMod.LOG.warn("[CableCompat] GT 线缆替换失败，尝试回滚 player={} pos=({},{},{}) oldMetaId={} newMetaId={}",
            player.getCommandSenderName(), tile.xCoord, tile.yCoord, tile.zCoord, oldId, newId, failure);
        try {
            invokeRequired(profile.setBaseMetaTileEntity, oldCable, base);
            invokeRequired(profile.setMetaTileId, base, Short.valueOf(oldId));
            profile.cableConnections.setByte(oldCable, oldConnections);
            profile.baseConnections.setByte(base, oldConnections);
            refreshPipe(base, tile);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError rollbackFailure) {
            MyMod.LOG.error("[CableCompat] GT 线缆回滚失败 pos=({},{},{})", tile.xCoord, tile.yCoord, tile.zCoord, rollbackFailure);
        }
    }

    private static List<ForgeDirection> sides(byte mask) {
        List<ForgeDirection> result = new ArrayList<ForgeDirection>();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) if ((mask & side.flag) != 0) result.add(side);
        return result;
    }

    private static byte byteValue(Object value) {
        return value instanceof Number ? ((Number) value).byteValue() : 0;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeRequired(Method method, Object target, Object... args) throws ReflectiveOperationException {
        return method.invoke(target, args);
    }

    interface ClassResolver { Class<?> resolve(String name); }

    private static final class ProductionClassResolver implements ClassResolver {
        @Override public Class<?> resolve(String name) { return ClassNameCompatSupport.resolveClass(name); }
    }

    /** 完整成功才启用的两代 GT 共同成员档案。 */
    static final class CapabilityProfile {
        final boolean complete;
        Class<?> gregTechTileType, metaTileType, basePipeType, cableType;
        Method getMetaTileId, setMetaTileId, getMetaTileEntity, getConnections, isConnectedAtSide;
        Method newMetaEntity, setBaseMetaTileEntity, causeCableUpdate, issueTextureUpdate, issueBlockUpdate, issueTileUpdate;
        Field baseConnections, cableConnections, material, cableLoss, amperage, voltage, blockMachines, metaTileEntities;

        private CapabilityProfile(boolean complete) { this.complete = complete; }

        static CapabilityProfile resolve(ClassResolver resolver) {
            CapabilityProfile p = new CapabilityProfile(false);
            try {
                Class<?> api = resolver.resolve("gregtech.api.GregTechAPI");
                p.metaTileType = resolver.resolve("gregtech.api.interfaces.metatileentity.IMetaTileEntity");
                p.gregTechTileType = resolver.resolve("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
                p.basePipeType = resolver.resolve("gregtech.api.metatileentity.BaseMetaPipeEntity");
                Class<?> metaPipeType = resolver.resolve("gregtech.api.metatileentity.MetaPipeEntity");
                p.cableType = resolver.resolve("gregtech.api.metatileentity.implementations.MTECable");
                if (api == null || p.metaTileType == null || p.gregTechTileType == null || p.basePipeType == null
                    || metaPipeType == null || p.cableType == null) return p;
                p.getMetaTileId = method(p.gregTechTileType, "getMetaTileID");
                p.setMetaTileId = method(p.gregTechTileType, "setMetaTileID", short.class);
                p.getMetaTileEntity = method(p.gregTechTileType, "getMetaTileEntity");
                p.getConnections = method(p.basePipeType, "getConnections");
                p.isConnectedAtSide = method(p.cableType, "isConnectedAtSide", ForgeDirection.class);
                p.newMetaEntity = method(p.cableType, "newMetaEntity", p.gregTechTileType);
                p.setBaseMetaTileEntity = method(p.metaTileType, "setBaseMetaTileEntity", p.gregTechTileType);
                p.causeCableUpdate = method(api, "causeCableUpdate", World.class, int.class, int.class, int.class);
                p.issueTextureUpdate = method(p.gregTechTileType, "issueTextureUpdate");
                p.issueBlockUpdate = method(p.gregTechTileType, "issueBlockUpdate");
                p.issueTileUpdate = method(p.gregTechTileType, "issueTileUpdate");
                p.baseConnections = field(p.basePipeType, "mConnections");
                p.cableConnections = field(metaPipeType, "mConnections");
                p.material = field(p.cableType, "mMaterial");
                p.cableLoss = field(p.cableType, "mCableLossPerMeter");
                p.amperage = field(p.cableType, "mAmperage");
                p.voltage = field(p.cableType, "mVoltage");
                p.blockMachines = field(api, "sBlockMachines");
                p.metaTileEntities = field(api, "METATILEENTITIES");
                return allPresent(p) ? copyComplete(p) : p;
            } catch (LinkageError | SecurityException ignored) {
                return p;
            }
        }

        private static CapabilityProfile copyComplete(CapabilityProfile source) {
            CapabilityProfile p = new CapabilityProfile(true);
            p.gregTechTileType=source.gregTechTileType; p.metaTileType=source.metaTileType; p.basePipeType=source.basePipeType; p.cableType=source.cableType;
            p.getMetaTileId=source.getMetaTileId; p.setMetaTileId=source.setMetaTileId; p.getMetaTileEntity=source.getMetaTileEntity; p.getConnections=source.getConnections;
            p.isConnectedAtSide=source.isConnectedAtSide; p.newMetaEntity=source.newMetaEntity; p.setBaseMetaTileEntity=source.setBaseMetaTileEntity;
            p.causeCableUpdate=source.causeCableUpdate; p.issueTextureUpdate=source.issueTextureUpdate; p.issueBlockUpdate=source.issueBlockUpdate; p.issueTileUpdate=source.issueTileUpdate;
            p.baseConnections=source.baseConnections; p.cableConnections=source.cableConnections; p.material=source.material; p.cableLoss=source.cableLoss;
            p.amperage=source.amperage; p.voltage=source.voltage; p.blockMachines=source.blockMachines; p.metaTileEntities=source.metaTileEntities;
            return p;
        }

        private static boolean allPresent(CapabilityProfile p) {
            return p.getMetaTileId!=null && p.setMetaTileId!=null && p.getMetaTileEntity!=null && p.getConnections!=null
                && p.isConnectedAtSide!=null && p.newMetaEntity!=null && p.setBaseMetaTileEntity!=null && p.causeCableUpdate!=null
                && p.issueTextureUpdate!=null && p.issueBlockUpdate!=null && p.issueTileUpdate!=null && p.baseConnections!=null
                && p.cableConnections!=null && p.material!=null && p.cableLoss!=null && p.amperage!=null && p.voltage!=null
                && p.blockMachines!=null && p.metaTileEntities!=null;
        }

        private static Method method(Class<?> owner, String name, Class<?>... args) {
            return ReflectiveMemberSupport.findMethodInHierarchy(owner, name, args);
        }
        private static Field field(Class<?> owner, String name) {
            return ReflectiveMemberSupport.findFieldInHierarchy(owner, name);
        }
    }
}
