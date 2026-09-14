package club.heiqi.qz_miner.client.picker;

import club.heiqi.config.ui.editor.PickerIconSource;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/**
 * 展示适配器（无状态、无缓存）：标签直读面板数据，图标经 {@link PickerIconSource} 按候选域 key 解析。
 *
 * <p>契约出处：（ADR 原稿在工作站、不在仓内） §1.7 D-6、§5.5、A6/A8；ADR §9.3
 * （原 {@code BlockPickerVisualAdapterTest} 的「快照语义」断言迁到图标源 / UILib 缓存层）。</p>
 *
 * <p><b>删除了什么</b>：无界 {@code HashMap} 图标缓存（D-6，改由 UILib 有界 {@code PickerIconCache} 承担）
 * 与硬编码 16×16 占位 {@code BufferedImage}（A6，占位下沉 UILib 渲染协议）。本类因此不再持有任何
 * 跨请求状态；生产路径上候选图标实际由 {@code PickerIconResolver}（UILib）经缓存调用图标源解析，
 * 本类只在「无 SPI 解析器」时作为回退面使用。</p>
 */
public final class BlockPickerVisualAdapter implements VisualAdapter {

    private final PickerIconSource iconSource;

    /**
     * @param iconSource 候选域图标源（非 null）
     */
    public BlockPickerVisualAdapter(PickerIconSource iconSource) {
        if (iconSource == null) {
            throw new IllegalArgumentException("iconSource must not be null");
        }
        this.iconSource = iconSource;
    }

    /** {@inheritDoc} 候选图标：走候选域图标源；无图返回 null（占位归 UILib）。 */
    @Override
    public SceneImageSource candidateImage(SearchPickerData.Candidate candidate) {
        return candidate == null ? null : iconSource.candidateIcon(candidate.key());
    }

    /**
     * {@inheritDoc} 变体图标：{@code Variant.key()} 形如 {@code registry@meta}，按最后一个 '@' 取候选键。
     *
     * <p><b>为什么这里可以拆键</b>：该键是 Miner 自己定义的投影
     * （{@code BlockPickerCandidateSource.toCandidate}），不是 UILib 的分级键空间；
     * ADR D-10/D-11 禁止的是对 {@code registryKey()}（Item 域）做拆键映射。</p>
     */
    @Override
    public SceneImageSource variantImage(SearchPickerData.Variant variant) {
        if (variant == null) {
            return null;
        }
        String key = variant.key();
        int separator = key == null ? -1 : key.lastIndexOf('@');
        if (separator <= 0 || separator == key.length() - 1) {
            return null;
        }
        return iconSource.variantIcon(key.substring(0, separator), key);
    }

    /** {@inheritDoc} */
    @Override
    public String candidateLabel(SearchPickerData.Candidate candidate) {
        return candidate.label();
    }

    /** {@inheritDoc} */
    @Override
    public String variantLabel(SearchPickerData.Variant variant) {
        return variant.label();
    }
}
