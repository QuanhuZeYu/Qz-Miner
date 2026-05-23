package club.heiqi.qz_miner.mixins;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Qz Miner Mixin 条件加载插件。
 */
public final class QzMinerMixinPlugin implements IMixinConfigPlugin {

    private static final Map<String, String> OPTIONAL_MIXIN_TARGETS = createOptionalMixinTargets();

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String requiredClassName = OPTIONAL_MIXIN_TARGETS.get(mixinClassName);
        return requiredClassName == null || isClassPresent(requiredClassName);
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

    private static Map<String, String> createOptionalMixinTargets() {
        Map<String, String> targets = new java.util.HashMap<String, String>();
        targets.put("club.heiqi.qz_miner.mixins.MixinTileEntityOres", "gregtech.common.blocks.TileEntityOres");
        targets.put("club.heiqi.qz_miner.mixins.MixinBWTileEntityMetaGeneratedOre", "bartworks.system.material.BWTileEntityMetaGeneratedOre");
        targets.put("club.heiqi.qz_miner.mixins.MixinBlockBaseOre", "gtPlusPlus.core.block.base.BlockBaseOre");
        return Collections.unmodifiableMap(targets);
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
