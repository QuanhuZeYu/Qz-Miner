package club.heiqi.qz_miner.mixins;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Qz Miner Mixin 条件加载插件。
 */
public final class QzMinerMixinPlugin implements IMixinConfigPlugin {

    private static final Map<String, TargetCapability> OPTIONAL_MIXIN_TARGETS = createOptionalMixinTargets();

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        TargetCapability capability = OPTIONAL_MIXIN_TARGETS.get(mixinClassName);
        return capability == null || hasMethod(capability);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static Map<String, TargetCapability> createOptionalMixinTargets() {
        Map<String, TargetCapability> targets = new java.util.HashMap<String, TargetCapability>();
        String adapterDescriptor = "(Ljava/util/Random;Lgregtech/common/GTProxy$OreDropSystem;Lgregtech/common/ores/OreInfo;I)Ljava/util/ArrayList;";
        targets.put("club.heiqi.qz_miner.mixins.MixinGTOreAdapter", new TargetCapability("gregtech.common.ores.GTOreAdapter", "getBigOreDrops", adapterDescriptor));
        targets.put("club.heiqi.qz_miner.mixins.MixinBWOreAdapter", new TargetCapability("gregtech.common.ores.BWOreAdapter", "getBigOreDrops", adapterDescriptor));
        targets.put("club.heiqi.qz_miner.mixins.MixinGTPPOreAdapter", new TargetCapability("gregtech.common.ores.GTPPOreAdapter", "getBigOreDrops", adapterDescriptor));
        targets.put("club.heiqi.qz_miner.mixins.MixinTileEntityOresLegacy", new TargetCapability("gregtech.common.blocks.TileEntityOres", "getDrops", "(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;", "gregtech/common/blocks/TileEntityOres", "mNatural", "Z"));
        targets.put("club.heiqi.qz_miner.mixins.MixinBWTileEntityMetaGeneratedOreLegacy", new TargetCapability("bartworks.system.material.BWTileEntityMetaGeneratedOre", "getDrops", "(I)Ljava/util/ArrayList;", "bartworks/system/material/BWTileEntityMetaGeneratedOre", "natural", "Z"));
        targets.put("club.heiqi.qz_miner.mixins.MixinBlockBaseOreLegacy", new TargetCapability("gtPlusPlus.core.block.base.BlockBaseOre", "getDrops", "(Lnet/minecraft/world/World;IIIII)Ljava/util/ArrayList;"));
        return Collections.unmodifiableMap(targets);
    }

    /** 按字节码方法表判断能力，不加载或初始化目标类。 */
    private static boolean hasMethod(TargetCapability capability) {
        return hasMethod(capability, QzMinerMixinPlugin::getClassBytes);
    }

    static boolean hasMethod(TargetCapability capability, ClassBytesProvider provider) {
        byte[] bytes = provider.getClassBytes(capability.className);
        if (bytes == null) return false;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!capability.methodName.equals(method.name) || !capability.descriptor.equals(method.desc)) continue;
                if (capability.fieldOwner == null) return true;
                for (org.spongepowered.asm.lib.tree.AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.GETFIELD) continue;
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (capability.fieldOwner.equals(field.owner) && capability.fieldName.equals(field.name)
                        && capability.fieldDescriptor.equals(field.desc)) return true;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
        return false;
    }

    private static byte[] getClassBytes(String className) {
        if (Launch.classLoader == null) return null;
        try {
            return Launch.classLoader.getClassBytes(className);
        } catch (IOException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    interface ClassBytesProvider {
        byte[] getClassBytes(String className);
    }

    static final class TargetCapability {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final String fieldOwner;
        private final String fieldName;
        private final String fieldDescriptor;

        private TargetCapability(String className, String methodName, String descriptor) {
            this(className, methodName, descriptor, null, null, null);
        }

        private TargetCapability(String className, String methodName, String descriptor, String fieldOwner,
                                 String fieldName, String fieldDescriptor) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.fieldOwner = fieldOwner;
            this.fieldName = fieldName;
            this.fieldDescriptor = fieldDescriptor;
        }
    }

    static TargetCapability capability(String className, String methodName, String descriptor) {
        return new TargetCapability(className, methodName, descriptor);
    }

    static TargetCapability capability(String className, String methodName, String descriptor, String fieldOwner,
                                       String fieldName, String fieldDescriptor) {
        return new TargetCapability(className, methodName, descriptor, fieldOwner, fieldName, fieldDescriptor);
    }

    /**
     * 判断目标类字节码是否存在。
     *
     * <p>Mixin 准备阶段不能用 Class.forName 检查目标类，否则会提前加载 Minecraft/模组类，
     * 可能导致其他 early mixin 的目标类已加载错误。</p>
     *
     * @param className 类名
     * @return 字节码存在时返回 true
     */
    private static boolean isClassPresent(String className) {
        if (Launch.classLoader != null) {
            try {
                return Launch.classLoader.getClassBytes(className) != null;
            } catch (IOException ignored) {
                return false;
            }
        }

        String classResourcePath = className.replace('.', '/') + ".class";
        try {
            return QzMinerMixinPlugin.class.getClassLoader().getResource(classResourcePath) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
