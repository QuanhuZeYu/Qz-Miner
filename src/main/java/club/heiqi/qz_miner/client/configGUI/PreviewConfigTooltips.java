package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 新增配置键的配置页 tooltip 本地化（中英对称）。
 *
 * <p>UILib 4.10 配置页把 {@link FieldSpec#helper()} 当字面量渲染，没有语言表通道。本类为
 * 预览相关的新增配置键注册「代理渲染器」：只把 helper（tooltip）替换为语言键
 * {@code config.qz_miner.<键名>.tooltip} 的当前语言文本，其余（label / 控件 / 约束 / 信号）
 * 完全交给原 {@link FieldRenderer} 处理；语言键缺失时回退 Schema 中文 helper，不改变任何旧字段。
 * 受覆盖的路径集合以 {@link #paths()} 为唯一事实源（新增键时同步该数组与两个语言文件）。</p>
 *
 * <p><b>删除条件</b>：UILib 配置页支持 label/helper 的 i18n（或 {@link FieldSpec} 增加语言键字段）后，
 * 删除本类与 {@code QzMinerConfigGUI} 中的 install 调用，只保留语言文件条目。</p>
 */
public final class PreviewConfigTooltips {

    /** 语言键前缀。 */
    public static final String KEY_PREFIX = "config.qz_miner.";

    /** 语言键后缀。 */
    public static final String KEY_SUFFIX = ".tooltip";

    /** 本轮新增配置键的完整 schema path（顺序与接口冻结 §E 一致）。 */
    private static final String[] PATHS = {
            "general.parallelBudgetMode",
            "general.parallelSliceBudgetMs",
            "client.clientPreviewRenderBackend",
            "client.clientPreviewBarThickness",
            "client.clientPreviewColorSource",
            "client.clientPreviewColorPrimary",
            "client.clientPreviewColorSecondary",
            "client.clientPreviewColorRemote",
            "client.clientPreviewColorTruncated",
            "client.clientPreviewDepthMode",
            "client.clientPreviewAnimation",
            "client.clientPreviewAnimationDurationMs",
            "client.clientPreviewAnimationPhase",
            "client.clientPreviewFadeMode",
            "client.clientPreviewFadeRefreshDistance",
            "client.clientPreviewFadeFallbackMs",
            "client.clientPreviewMinScreenWidthPx",
            "client.clientPreviewOutlineWidthPx",
            "client.clientPreviewTruncationSignal",
            "client.clientPreviewMaxTargetsHardCap",
            "client.clientPreviewLod",
            "client.clientPreviewLodMinAlpha",
            "client.clientPreviewSuppressVanillaHighlight",
            "client.clientPreviewVersionedInputs",
            "client.clientPreviewPresentationOverlay",
            "client.clientPreviewExecutionProgress",
            "client.clientPreviewBackendDiagnostics",
            "client.clientPreviewRemoteTimeoutMs"
    };

    private PreviewConfigTooltips() {
    }

    /** 语言文本解析边界（生产实现走 {@link ClientI18n}，测试注入假实现）。 */
    public interface TextResolver {
        /**
         * @param key 语言键
         * @return 当前语言文本；缺失时按原版 {@code StatCollector} 语义返回 key 本身
         */
        String resolve(String key);
    }

    /** @return 受本地化覆盖的完整 schema path（副本，稳定顺序） */
    public static String[] paths() {
        return PATHS.clone();
    }

    /**
     * 为新增配置键注册本地化代理渲染器（使用生产 {@link ClientI18n} 解析）。
     *
     * @param registry 配置页字段渲染器注册表
     */
    public static void install(FieldRendererRegistry registry) {
        install(registry, new ClientI18nResolver());
    }

    /**
     * 为新增配置键注册本地化代理渲染器。
     *
     * @param registry 配置页字段渲染器注册表
     * @param resolver 语言文本解析边界
     */
    public static void install(FieldRendererRegistry registry, TextResolver resolver) {
        if (registry == null || resolver == null) {
            throw new IllegalArgumentException("registry 与 resolver 均不可为 null");
        }
        FieldRendererRegistry delegates = FieldRendererRegistry.defaultRegistry();
        LocalizingRenderer renderer = new LocalizingRenderer(delegates, resolver);
        for (String path : PATHS) {
            registry.registerPath(path, renderer);
        }
    }

    /**
     * @param path 字段完整 schema path
     * @return 该字段的 tooltip 语言键（{@code config.qz_miner.<键名>.tooltip}）
     */
    public static String tooltipKey(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path 不可为 null");
        }
        int dot = path.lastIndexOf('.');
        String leaf = dot < 0 ? path : path.substring(dot + 1);
        return KEY_PREFIX + leaf + KEY_SUFFIX;
    }

    /**
     * 语言文本可用时返回仅替换 helper 的 {@link FieldSpec} 副本，否则原样返回（零分配）。
     *
     * @param spec     原字段元数据
     * @param resolver 语言文本解析边界
     * @return 生效字段元数据
     */
    static FieldSpec localized(FieldSpec spec, TextResolver resolver) {
        if (spec == null || resolver == null) {
            throw new IllegalArgumentException("spec 与 resolver 均不可为 null");
        }
        String key = tooltipKey(spec.path());
        String text = resolver.resolve(key);
        if (text == null || text.isEmpty() || key.equals(text)) {
            return spec;
        }
        return new FieldSpec(spec.path(), spec.type(), spec.defaultValue(), spec.constraints(),
                spec.label(), text, spec.widget(), spec.valueSpec());
    }

    /** 只替换 helper 的代理渲染器；其余字段与信号全部透传原实现。 */
    private static final class LocalizingRenderer implements FieldRenderer {

        private final FieldRendererRegistry delegates;
        private final TextResolver resolver;

        private LocalizingRenderer(FieldRendererRegistry delegates, TextResolver resolver) {
            this.delegates = delegates;
            this.resolver = resolver;
        }

        @Override
        public SceneNode render(SceneRuntime rt, FieldSpec spec, DraftSignalAdapter adapter) {
            FieldRenderer delegate = delegates.resolve(spec);
            if (delegate == null) {
                throw new IllegalStateException("缺少默认字段渲染器: " + spec.path());
            }
            return delegate.render(rt, localized(spec, resolver), adapter);
        }
    }

    /** 生产解析器：走 Miner 既有 {@link ClientI18n}，不新增第二套语言表。 */
    private static final class ClientI18nResolver implements TextResolver {

        @Override
        public String resolve(String key) {
            return ClientI18n.tr(key);
        }
    }
}
