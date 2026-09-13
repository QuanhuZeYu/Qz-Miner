package club.heiqi.qz_miner.chain.client.render;

import java.util.Locale;

/**
 * 后端选择纯函数（无 GL、无状态）。
 *
 * <p>决策表：</p>
 * <ul>
 *   <li>{@code auto}：能力探测通过且上次未失败 → {@code shader}，否则 {@code legacy}<br>
 *   <li>{@code shader}：探测失败 / 上次失败时同样回退 {@code legacy}（配 {@link #explain} 记录诊断）<br>
 *   <li>{@code legacy}：恒 {@code legacy}<br>
 *   <li>null / 空 / 未知值：按 {@code auto} 处理；caps 为 null：{@code legacy}</li>
 * </ul>
 *
 * <p>只看真实 GL 能力与上次失败；不因加载了 Angelica / shader pack 而放弃 shader（Lead 裁定）。</p>
 */
public final class ChainPreviewBackendSelector {

    public static final String AUTO = "auto";
    public static final String SHADER = "shader";
    public static final String LEGACY = "legacy";

    private ChainPreviewBackendSelector() {
    }

    /**
     * 纯函数：选择本帧后端 id。
     *
     * @param configured        配置值（auto / shader / legacy，大小写与空白容忍）
     * @param capabilities      能力探测结果，可为 null
     * @param lastAttemptFailed 上一次 shader 后端加载或初始化是否失败（失败后不每帧重试）
     * @return {@code "shader"} 或 {@code "legacy"}
     */
    public static String select(
            String configured,
            ChainPreviewGlCapabilities capabilities,
            boolean lastAttemptFailed) {
        String requested = normalize(configured);
        if (LEGACY.equals(requested)) {
            return LEGACY;
        }
        if (capabilities == null || !capabilities.isShaderSupported()) {
            return LEGACY;
        }
        if (lastAttemptFailed) {
            return LEGACY;
        }
        return SHADER;
    }

    /**
     * 纯函数：给出本次选择的简短原因，供渲染器一次性诊断日志使用。
     *
     * @param configured        配置值
     * @param capabilities      能力探测结果，可为 null
     * @param lastAttemptFailed 上次 shader 尝试是否失败
     * @return 原因文本
     */
    public static String explain(
            String configured,
            ChainPreviewGlCapabilities capabilities,
            boolean lastAttemptFailed) {
        String requested = normalize(configured);
        if (LEGACY.equals(requested)) {
            return "configured=legacy";
        }
        if (capabilities == null) {
            return "capabilities unavailable";
        }
        if (!capabilities.isShaderSupported()) {
            return "shader unsupported (" + capabilities.describe() + ")";
        }
        if (lastAttemptFailed) {
            return "previous shader attempt failed";
        }
        return "shader supported";
    }

    /**
     * 规范化配置值：null / 空 / 未知 → {@code auto}。
     *
     * @param configured 配置值，可为 null
     * @return {@code auto} / {@code shader} / {@code legacy}
     */
    static String normalize(String configured) {
        if (configured == null) {
            return AUTO;
        }
        String value = configured.trim().toLowerCase(Locale.ROOT);
        if (SHADER.equals(value)) {
            return SHADER;
        }
        if (LEGACY.equals(value)) {
            return LEGACY;
        }
        return AUTO;
    }
}
