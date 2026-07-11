package club.heiqi.qz_miner.client.picker;

import java.util.HashMap;
import java.util.Map;

import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/** 每个配置 screen 独立持有的 ItemStack 图标快照适配器。 */
public final class BlockPickerVisualAdapter implements VisualAdapter {
    private final Map<String, HostImageSource> candidates = new HashMap<String, HostImageSource>();
    private final Map<String, HostImageSource> variants = new HashMap<String, HostImageSource>();

    public BlockPickerVisualAdapter(java.util.List<BlockCandidate> source) {
        for (BlockCandidate candidate : source) {
            if (candidate.representative() != null) {
                candidates.put(candidate.registry(), HostImageSource.itemStack(candidate.representative()));
            }
            for (BlockVariant variant : candidate.variants()) {
                if (variant.stack() != null) {
                    variants.put(candidate.registry() + "@" + variant.metadata(), HostImageSource.itemStack(variant.stack()));
                }
            }
        }
    }

    public SceneImageSource candidateImage(SearchPickerData.Candidate candidate) {
        return candidates.get(candidate.key());
    }

    public SceneImageSource variantImage(SearchPickerData.Variant variant) {
        return variants.get(variant.key());
    }

    public String candidateLabel(SearchPickerData.Candidate candidate) { return candidate.label(); }
    public String variantLabel(SearchPickerData.Variant variant) { return variant.label(); }
}
