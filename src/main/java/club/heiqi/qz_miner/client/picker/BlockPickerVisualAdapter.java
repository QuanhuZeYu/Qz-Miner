package club.heiqi.qz_miner.client.picker;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
    private final SceneImageSource placeholder = createPlaceholder();

    public BlockPickerVisualAdapter(java.util.List<BlockCandidate> source) {
        for (BlockCandidate candidate : source) {
            if (candidate.representative() != null) {
                candidates.put(candidate.registry(), HostImageSource.itemIcon(candidate.representative()));
            }
            for (BlockVariant variant : candidate.variants()) {
                if (variant.stack() != null) {
                    variants.put(candidate.registry() + "@" + variant.metadata(),
                            HostImageSource.itemIcon(variant.stack()));
                }
            }
        }
    }

    public SceneImageSource candidateImage(SearchPickerData.Candidate candidate) {
        SceneImageSource image = candidates.get(candidate.key());
        return image == null ? placeholder : image;
    }

    public SceneImageSource variantImage(SearchPickerData.Variant variant) {
        SceneImageSource image = variants.get(variant.key());
        return image == null ? placeholder : image;
    }

    public String candidateLabel(SearchPickerData.Candidate candidate) { return candidate.label(); }
    public String variantLabel(SearchPickerData.Variant variant) { return variant.label(); }

    /** 创建当前 screen 独享的无物品占位图片源。 */
    private static SceneImageSource createPlaceholder() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0xFF3A3A3A, true));
            graphics.fillRect(0, 0, 16, 16);
            graphics.setColor(new Color(0xFFB8B8B8, true));
            graphics.drawRect(0, 0, 15, 15);
            graphics.drawLine(4, 4, 11, 11);
            graphics.drawLine(11, 4, 4, 11);
        } finally {
            graphics.dispose();
        }
        return HostImageSource.bufferedImage(image, "qz-miner-block-picker-placeholder");
    }
}
