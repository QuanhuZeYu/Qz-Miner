package club.heiqi.qz_miner.client.picker;

import club.heiqi.config.ui.editor.PickerIconSource;
import club.heiqi.uilib.ui.image.HostImageSource;
import club.heiqi.uilib.ui.scene.image.SceneImageSource;

/**
 * 方块选择器图标源（纯函数，无缓存、无状态）：候选域 key → 可渲染物品图标。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §1.2(A6/A8)/§5.1/§5.5；
 * UILib {@code PickerIconSource} javadoc。要点：</p>
 * <ul>
 *   <li><b>入口是候选域 key</b>（不要求先物化 Candidate/Variant）：候选级用代表变体栈，
 *       变体级按 meta 取该变体栈；</li>
 *   <li><b>缓存归 UILib</b>（{@code PickerIconCache}，有界 LRU 512/1024，A8/D-6）：本类不建任何 Map，
 *       每次调用新建 {@link HostImageSource}（其工厂自带 {@code ItemStack.copy()} 快照语义）；</li>
 *   <li><b>占位下沉 UILib</b>（A6）：无图返回 {@code null}，由 UILib 消费点按「无图」协议色渲染，
 *       Miner 不再自建 16×16 BufferedImage；</li>
 *   <li><b>只允许客户端主线程</b>（{@link BlockPickerCandidateSource#blockCandidate(String)} 入口断言）。</li>
 * </ul>
 *
 * <p><b>U-A9 现状（ADR 与源码事实冲突，已上报 Lead）</b>：ADR §5.1(Z-2) 要求本方法返回的
 * {@link SceneImageSource} 覆写 {@code registryKey()} 为候选域键，但 UILib 侧
 * {@code HostImageSource} 是 {@code final} 且键由内部 ItemStack 自算（{@code Item 注册名:meta}），
 * 而渲染唯一入口 {@code UiRenderContext.drawImage} 只绘制 {@code instanceof HostImageSource} 的对象。
 * 因此当前实现取「渲染正确」优先：返回 HostImageSource，键暂为 Item 域；待 UILib 提供
 * 「显式候选域键」的图标工厂后，本类两处工厂调用改为携带 {@code PickerIconKey} 值即可闭环，
 * 调用面不变（详见 {@code team/P2B-实现记录.md} 未决项 U-B2）。</p>
 */
public final class BlockPickerIconSource implements PickerIconSource {

    private static final char VARIANT_SEPARATOR = '@';

    private final BlockPickerCandidateSource source;

    /**
     * @param source 候选源（提供候选域分片：代表栈与变体栈）
     */
    public BlockPickerIconSource(BlockPickerCandidateSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
    }

    /** {@inheritDoc} 候选级图标 = 代表变体栈；无图返回 null（占位归 UILib）。 */
    @Override
    public SceneImageSource candidateIcon(String candidateKey) {
        BlockCandidate candidate = source.blockCandidate(candidateKey);
        if (candidate == null || candidate.representative() == null) {
            return null;
        }
        return HostImageSource.itemIcon(candidate.representative());
    }

    /**
     * {@inheritDoc} 变体级图标 = 该 meta 的变体栈。
     *
     * <p><b>变体标识口径（契约澄清）</b>：SPI 参数名写「variantKey」，而其唯一消费者
     * {@code PickerIconCache} 把该值交给 {@code PickerIconKey.variant(candidateKey, 该值)} 当
     * <b>meta 令牌</b>拼接。为抵消该歧义，本实现同时接受两种形态：</p>
     * <ul>
     *   <li>meta 令牌（如 {@code "3"}）—— 与 {@code PickerIconKey.variant} 语义一致；</li>
     *   <li>完整变体键（如 {@code "minecraft:stone@3"}）—— 与 {@code SearchPickerData.Variant.key()} 一致，
     *       前缀等于 candidateKey 时剥离后取 meta。</li>
     * </ul>
     */
    @Override
    public SceneImageSource variantIcon(String candidateKey, String variantKeyOrNull) {
        if (variantKeyOrNull == null || variantKeyOrNull.isEmpty()) {
            return candidateIcon(candidateKey);
        }
        BlockCandidate candidate = source.blockCandidate(candidateKey);
        if (candidate == null) {
            return null;
        }
        int meta = metadataOf(candidateKey, variantKeyOrNull);
        if (meta < 0) {
            return null;
        }
        for (BlockVariant variant : candidate.variants()) {
            if (variant.metadata() == meta && variant.stack() != null) {
                return HostImageSource.itemIcon(variant.stack());
            }
        }
        return null;
    }

    /** meta 令牌解析：容忍「完整变体键」形态（candidateKey + '@' + meta）；非法返回 -1。 */
    static int metadataOf(String candidateKey, String variantSpec) {
        String token = variantSpec;
        if (candidateKey != null && variantSpec.length() > candidateKey.length() + 1
                && variantSpec.startsWith(candidateKey)
                && variantSpec.charAt(candidateKey.length()) == VARIANT_SEPARATOR) {
            token = variantSpec.substring(candidateKey.length() + 1);
        }
        if (token.isEmpty()) {
            return -1;
        }
        try {
            int meta = Integer.parseInt(token);
            return meta < 0 ? -1 : meta;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
