package club.heiqi.qz_miner.client.picker;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/**
 * 每个配置 screen 独立持有的 ItemStack 图标快照适配器。
 *
 * <p>候选索引与 {@link BlockPickerProvider} 共享（同一份不可变注册表快照，不再重复建索引）；</p>
 * <p>HostImageSource 按首次请求懒建并缓存（不再构造期全量建图），未使用条目不产生图标包装对象。</p>
 */
public final class BlockPickerVisualAdapter implements VisualAdapter {
    private final Map<String, BlockCandidate> candidateIndex;
    private final Map<String, HostImageSource> iconCache = new HashMap<String, HostImageSource>();
    private final SceneImageSource placeholder = createPlaceholder();

    /** @param candidateIndex 与 Provider 共享的 registry → BlockCandidate 不可变索引 */
    public BlockPickerVisualAdapter(Map<String, BlockCandidate> candidateIndex) {
        this.candidateIndex = Collections.unmodifiableMap(new HashMap<String, BlockCandidate>(candidateIndex));
    }

    public SceneImageSource candidateImage(SearchPickerData.Candidate candidate) {
        HostImageSource image = iconCache.get(candidate.key());
        if (image == null) {
            BlockCandidate source = candidateIndex.get(candidate.key());
            image = source != null && source.representative() != null
                    ? HostImageSource.itemIcon(source.representative()) : null;
            if (image != null) {
                iconCache.put(candidate.key(), image);
            }
        }
        return image == null ? placeholder : image;
    }

    public SceneImageSource variantImage(SearchPickerData.Variant variant) {
        String key = variant.key();
        HostImageSource image = iconCache.get(key);
        if (image == null) {
            image = buildVariantIcon(key);
            if (image != null) {
                iconCache.put(key, image);
            }
        }
        return image == null ? placeholder : image;
    }

    public String candidateLabel(SearchPickerData.Candidate candidate) { return candidate.label(); }
    public String variantLabel(SearchPickerData.Variant variant) { return variant.label(); }

    /** 按 key（registry@meta）反查变体并懒建图标；查不到返回 null（回退占位）。 */
    private HostImageSource buildVariantIcon(String variantKey) {
        int separator = variantKey == null ? -1 : variantKey.lastIndexOf('@');
        if (separator <= 0 || separator == variantKey.length() - 1) {
            return null;
        }
        String registry = variantKey.substring(0, separator);
        BlockCandidate source = candidateIndex.get(registry);
        if (source == null) {
            return null;
        }
        int meta;
        try {
            meta = Integer.parseInt(variantKey.substring(separator + 1));
        } catch (NumberFormatException invalid) {
            return null;
        }
        for (BlockVariant variant : source.variants()) {
            if (variant.metadata() == meta && variant.stack() != null) {
                return HostImageSource.itemIcon(variant.stack());
            }
        }
        return null;
    }

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
