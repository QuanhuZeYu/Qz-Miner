package club.heiqi.qz_miner.mixins;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.JumpInsnNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Qz Miner Mixin 条件加载插件。
 */
public final class QzMinerMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("qz_miner");
    private static final Map<String, TargetCapability> OPTIONAL_MIXIN_TARGETS = createOptionalMixinTargets();
    private static final Set<String> LOGGED_OPTIONAL_MIXINS =
            Collections.synchronizedSet(new HashSet<String>());

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
        if (capability == null) return true;
        CapabilityDecision decision = inspectCapability(capability, QzMinerMixinPlugin::getClassBytes);
        if (LOGGED_OPTIONAL_MIXINS.add(mixinClassName)) {
            LOGGER.info(decisionLogMessage(mixinClassName, capability, decision));
        }
        return decision.apply();
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
        String oreDropsDescriptor = "(Ljava/util/Random;Lgregtech/common/ores/OreInfo;ZI)Ljava/util/ArrayList;";
        targets.put("club.heiqi.qz_miner.mixins.MixinGTOreAdapter", new TargetCapability("gregtech.common.ores.GTOreAdapter", "getBigOreDrops", adapterDescriptor, "getOreDrops", oreDropsDescriptor, "gregtech/common/ores/OreInfo", "isNatural", "Z", 4));
        targets.put("club.heiqi.qz_miner.mixins.MixinBWOreAdapter", new TargetCapability("gregtech.common.ores.BWOreAdapter", "getBigOreDrops", adapterDescriptor, "getOreDrops", oreDropsDescriptor, "gregtech/common/ores/OreInfo", "isNatural", "Z", 4));
        targets.put("club.heiqi.qz_miner.mixins.MixinGTPPOreAdapter", new TargetCapability("gregtech.common.ores.GTPPOreAdapter", "getBigOreDrops", adapterDescriptor, null, null, null, 4));
        targets.put("club.heiqi.qz_miner.mixins.MixinTileEntityOresLegacy", new TargetCapability("gregtech.common.blocks.TileEntityOres", "getDrops", "(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;", "gregtech/common/blocks/TileEntityOres", "mNatural", "Z", 2));
        targets.put("club.heiqi.qz_miner.mixins.MixinBWTileEntityMetaGeneratedOreLegacy", new TargetCapability("bartworks.system.material.BWTileEntityMetaGeneratedOre", "getDrops", "(I)Ljava/util/ArrayList;", "bartworks/system/material/BWTileEntityMetaGeneratedOre", "natural", "Z", 1));
        targets.put("club.heiqi.qz_miner.mixins.MixinBlockBaseOreLegacy", new TargetCapability("gtPlusPlus.core.block.base.BlockBaseOre", "getDrops", "(Lnet/minecraft/world/World;IIIII)Ljava/util/ArrayList;", null, null, null, 6));
        return Collections.unmodifiableMap(targets);
    }

    /** 按字节码方法表判断能力，不加载或初始化目标类。 */
    private static boolean hasMethod(TargetCapability capability) {
        return inspectCapability(capability, QzMinerMixinPlugin::getClassBytes).apply();
    }

    static boolean hasMethod(TargetCapability capability, ClassBytesProvider provider) {
        return inspectCapability(capability, provider).apply();
    }

    /** 只读取目标字节码，返回不触发目标类加载的纯能力决策。 */
    static CapabilityDecision inspectCapability(TargetCapability capability, ClassBytesProvider provider) {
        byte[] bytes = provider.getClassBytes(capability.className);
        if (bytes == null) return CapabilityDecision.skip(DecisionReason.TARGET_BYTES_MISSING);
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!capability.methodName.equals(method.name) || !capability.descriptor.equals(method.desc)) continue;
                return hasRequiredField(node, capability) && hasFortuneClamp(method, capability.fortuneLocal)
                        ? CapabilityDecision.apply(DecisionReason.CAPABILITY_PRESENT)
                        : CapabilityDecision.skip(DecisionReason.METHOD_OR_SHAPE_MISMATCH);
            }
        } catch (RuntimeException | LinkageError ignored) {
            return CapabilityDecision.skip(DecisionReason.INSPECTION_FAILED);
        }
        return CapabilityDecision.skip(DecisionReason.METHOD_OR_SHAPE_MISMATCH);
    }

    /** 构造固定字段的启动期 fortune 能力门日志。 */
    static String decisionLogMessage(String mixinClassName, TargetCapability capability,
            CapabilityDecision decision) {
        return "[Compat][Fortune] mixin=" + mixinClassName
                + " target=" + capability.className
                + " apply=" + decision.apply()
                + " reason=" + decision.reason();
    }

    private static boolean hasRequiredField(ClassNode node, TargetCapability capability) {
        if (capability.fieldOwner == null) return true;
        for (MethodNode method : node.methods) {
            if (!capability.fieldMethodName.equals(method.name)
                || !capability.fieldMethodDescriptor.equals(method.desc)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.GETFIELD) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (capability.fieldOwner.equals(field.owner) && capability.fieldName.equals(field.name)
                    && capability.fieldDescriptor.equals(field.desc)) return true;
            }
        }
        return false;
    }

    /** 精确识别上游对指定 fortune 参数执行三级夹断的字节码形状。 */
    private static boolean hasFortuneClamp(MethodNode method, int fortuneLocal) {
        if (fortuneLocal < 0) return true;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode first = instructions[i];
            if (!(first instanceof VarInsnNode) || first.getOpcode() != Opcodes.ILOAD
                || ((VarInsnNode) first).var != fortuneLocal) continue;
            AbstractInsnNode constant = nextCodeInstruction(first);
            AbstractInsnNode jump = nextCodeInstruction(constant);
            AbstractInsnNode replacement = nextCodeInstruction(jump);
            AbstractInsnNode store = nextCodeInstruction(replacement);
            if (constant != null && constant.getOpcode() == Opcodes.ICONST_3
                && jump instanceof JumpInsnNode && jump.getOpcode() == Opcodes.IF_ICMPLE
                && replacement != null && replacement.getOpcode() == Opcodes.ICONST_3
                && store instanceof VarInsnNode && store.getOpcode() == Opcodes.ISTORE
                && ((VarInsnNode) store).var == fortuneLocal
                && isClampExit((JumpInsnNode) jump, store)) return true;
        }
        return false;
    }

    private static AbstractInsnNode nextCodeInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    /** 校验夹断分支只能越过写回块，并落到写回后的首条有效指令。 */
    private static boolean isClampExit(JumpInsnNode jump, AbstractInsnNode store) {
        AbstractInsnNode cursor = store.getNext();
        boolean foundTarget = false;
        while (cursor != null && cursor.getOpcode() < 0) {
            if (cursor == jump.label) foundTarget = true;
            cursor = cursor.getNext();
        }
        return foundTarget && nextCodeInstruction(jump.label) == cursor;
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

    /** 不可变的可选 Mixin 能力门决策。 */
    static final class CapabilityDecision {
        private final boolean apply;
        private final DecisionReason reason;

        private CapabilityDecision(boolean apply, DecisionReason reason) {
            this.apply = apply;
            this.reason = reason;
        }

        private static CapabilityDecision apply(DecisionReason reason) {
            return new CapabilityDecision(true, reason);
        }

        private static CapabilityDecision skip(DecisionReason reason) {
            return new CapabilityDecision(false, reason);
        }

        boolean apply() {
            return apply;
        }

        String reason() {
            return reason.wireName;
        }
    }

    /** 能力门的固定诊断原因。 */
    private enum DecisionReason {
        CAPABILITY_PRESENT("capability-present"),
        TARGET_BYTES_MISSING("target-bytes-missing"),
        METHOD_OR_SHAPE_MISMATCH("method-or-shape-mismatch"),
        INSPECTION_FAILED("inspection-failed");

        private final String wireName;

        DecisionReason(String wireName) {
            this.wireName = wireName;
        }
    }

    static final class TargetCapability {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final String fieldOwner;
        private final String fieldMethodName;
        private final String fieldMethodDescriptor;
        private final String fieldName;
        private final String fieldDescriptor;
        private final int fortuneLocal;

        private TargetCapability(String className, String methodName, String descriptor) {
            this(className, methodName, descriptor, null, null, null, null, null, -1);
        }

        private TargetCapability(String className, String methodName, String descriptor, String fieldOwner,
                                  String fieldName, String fieldDescriptor, int fortuneLocal) {
            this(className, methodName, descriptor, methodName, descriptor, fieldOwner, fieldName, fieldDescriptor, fortuneLocal);
        }

        private TargetCapability(String className, String methodName, String descriptor, String fieldMethodName,
                                 String fieldMethodDescriptor, String fieldOwner, String fieldName,
                                 String fieldDescriptor, int fortuneLocal) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.fieldMethodName = fieldMethodName;
            this.fieldMethodDescriptor = fieldMethodDescriptor;
            this.fieldOwner = fieldOwner;
            this.fieldName = fieldName;
            this.fieldDescriptor = fieldDescriptor;
            this.fortuneLocal = fortuneLocal;
        }
    }

    static TargetCapability capability(String className, String methodName, String descriptor) {
        return new TargetCapability(className, methodName, descriptor);
    }

    static TargetCapability capability(String className, String methodName, String descriptor, String fieldOwner,
                                       String fieldName, String fieldDescriptor) {
        return new TargetCapability(className, methodName, descriptor, fieldOwner, fieldName, fieldDescriptor, -1);
    }

    static TargetCapability capability(String className, String methodName, String descriptor, String fieldOwner,
                                       String fieldName, String fieldDescriptor, int fortuneLocal) {
        return new TargetCapability(className, methodName, descriptor, fieldOwner, fieldName, fieldDescriptor, fortuneLocal);
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
