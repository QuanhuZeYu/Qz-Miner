package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.tileentity.TileEntity;

/**
 * 通过 GregTech 元实体信息判定 TileEntity 身份的适配器。
 */
public final class ReflectiveGregTechTileIdentityCompatAdapter implements TileIdentityCompatAdapter {

    private final Class<?> gregTechTileEntityType;

    /**
     * 创建 GregTech TileEntity 身份适配器。
     */
    public ReflectiveGregTechTileIdentityCompatAdapter() {
        this.gregTechTileEntityType = ClassNameCompatSupport.resolveClass("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
    }

    @Override
    public boolean isAvailable() {
        return gregTechTileEntityType != null;
    }

    @Override
    public boolean supports(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        return ClassNameCompatSupport.isInstance(gregTechTileEntityType, sampleTileEntity)
            && ClassNameCompatSupport.isInstance(gregTechTileEntityType, targetTileEntity);
    }

    @Override
    public boolean matches(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        if (invokeInt(sampleTileEntity, "getMetaTileID") != invokeInt(targetTileEntity, "getMetaTileID")) {
            return false;
        }

        Object sampleMetaTileEntity = invoke(sampleTileEntity, "getMetaTileEntity");
        Object targetMetaTileEntity = invoke(targetTileEntity, "getMetaTileEntity");
        if (sampleMetaTileEntity == null || targetMetaTileEntity == null) {
            return sampleMetaTileEntity == targetMetaTileEntity;
        }
        return sampleMetaTileEntity.getClass() == targetMetaTileEntity.getClass();
    }

    private int invokeInt(Object owner, String methodName) {
        Object value = invoke(owner, methodName);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    private Object invoke(Object owner, String methodName) {
        if (owner == null) {
            return null;
        }

        try {
            Method method = owner.getClass().getMethod(methodName);
            return method.invoke(owner);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored) {
            return null;
        }
    }
}
