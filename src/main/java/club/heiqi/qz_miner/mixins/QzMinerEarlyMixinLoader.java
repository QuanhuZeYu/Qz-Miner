package club.heiqi.qz_miner.mixins;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Early Mixin 加载器。
 *
 * 用于加载原版类（如 NetHandlerPlayServer）的 mixin。
 * 原版 mixin 必须在 early 阶段加载，因为原版类在模组加载之前就已经被加载。
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
public class QzMinerEarlyMixinLoader implements IEarlyMixinLoader, IFMLLoadingPlugin {

    @Override
    public String getMixinConfig() {
        return "mixins.qz_miner.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        return Arrays.asList("MixinNetHandlerPlayServer", "MixinTickEvent");
    }

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
