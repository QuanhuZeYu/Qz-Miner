package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 新增配置键的配置页文本本地化（tooltip + label，中英对称）。
 *
 * <p><b>已核实的 UILib 4.10 缺口（本轮复查结论）</b>：{@link FieldSpec} 的 {@code label} 与
 * {@code helper} 都是字面量字符串，{@code FieldRenderSupport.labelOf(spec)} 原样返回
 * {@code spec.label()}、{@code FieldShellBinder} 原样透传 {@code spec.helper()}；
 * UILib 侧唯一的语言设施是 {@code club.heiqi.uilib.i18n.LanguageEpochService}——那是
 * 「语言代际」失效通道，不是文本解析通道。<b>因此 UILib 没有 label 语言表通道</b>。</p>
 *
 * <p>本类沿用仓库既有的 Miner 侧代理（不新造第二套机制）：为预览相关的新增配置键注册
 * 「代理渲染器」，把 helper 与 label 替换为语言键 {@code config.qz_miner.<键名>.tooltip} /
 * {@code config.qz_miner.<键名>.label} 的当前语言文本，其余（控件 / 约束 / 信号 / 值语义）
 * 完全交给原 {@link FieldRenderer} 处理；语言键缺失时回退 Schema 原文（label 回退原始键名），
 * 不改变任何旧字段。受覆盖的路径集合以 {@link #paths()} 为唯一事实源（新增键时同步该数组与
 * 两个语言文件）。</p>
 *
 * <p><b>删除条件</b>：UILib 配置页支持 label/helper 的 i18n（或 {@link FieldSpec} 增加语言键字段）后，
 * 删除本类与 {@code QzMinerConfigGUI} 中的 install 调用，只保留语言文件条目。</p>
 */
public final class PreviewConfigTooltips {

    /** 语言键前缀。 */
    public static final String KEY_PREFIX = "config.qz_miner.";

    /** tooltip 语言键后缀。 */
    public static final String KEY_SUFFIX = ".tooltip";

    /** label 语言键后缀。 */
    public static final String KEY_SUFFIX_LABEL = ".label";

    /** 本轮新增配置键的完整 schema path（顺序与配置键位一致）。 */
    private static final String[] PATHS = {
            "general.parallelBudgetMode",
            "general.parallelSliceBudgetMs",
            "general.harvestExhaustionPerBlock",
            "client.clientPreviewRenderBackend",
            "client.clientPreviewBarThickness",
            "client.clientPreviewColorSource",
            "client.clientPreviewColorChain",
            "client.clientPreviewColorArea",
            "client.clientPreviewColorInteract",
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
            "client.clientPreviewFaceShading",
            "client.clientPreviewTruncationSignal",
            "client.clientPreviewMaxTargetsHardCap",
            "client.clientPreviewLod",
            "client.clientPreviewLodMinAlpha",
            "client.clientPreviewOrderMinBrightness",
            "client.clientPreviewInteriorDim",
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
     * 为新增配置键注册本地化代理渲染器：只把 helper / label 换成语言文本，控件实现由类型默认渲染器
     * 解析（schema 以 {@code .color(...)} 声明的颜色键因此仍按 widget 分发到 HEX 输入框）。
     *
     * @param registry 配置页字段渲染器注册表
     * @param resolver 语言文本解析边界
     */
    public static void install(FieldRendererRegistry registry, TextResolver resolver) {
        if (registry == null || resolver == null) {
            throw new IllegalArgumentException("registry 与 resolver 均不可为 null");
        }
        LocalizingRenderer renderer = new LocalizingRenderer(resolver);
        for (String path : PATHS) {
            registry.registerPath(path, renderer);
        }
    }

    /**
     * @param path 字段完整 schema path
     * @return 该字段的 tooltip 语言键（{@code config.qz_miner.<键名>.tooltip}）
     */
    public static String tooltipKey(String path) {
        return languageKey(path, KEY_SUFFIX);
    }

    /**
     * @param path 字段完整 schema path
     * @return 该字段的 label 语言键（{@code config.qz_miner.<键名>.label}）
     */
    public static String labelKey(String path) {
        return languageKey(path, KEY_SUFFIX_LABEL);
    }

    /**
     * 语言文本可用时返回替换了 helper / label 的 {@link FieldSpec} 副本，否则原样返回（零分配）。
     *
     * <p>两个文本各判各的：只命中 label 时 helper 保持 Schema 原文，反之亦然；两者都未命中
     * 直接返回原实例（不制造等价副本）。</p>
     *
     * @param spec     原字段元数据
     * @param resolver 语言文本解析边界
     * @return 生效字段元数据
     */
    static FieldSpec localized(FieldSpec spec, TextResolver resolver) {
        if (spec == null || resolver == null) {
            throw new IllegalArgumentException("spec 与 resolver 均不可为 null");
        }
        String helper = resolveOrNull(resolver, tooltipKey(spec.path()));
        String label = resolveOrNull(resolver, labelKey(spec.path()));
        if (helper == null && label == null) {
            return spec;
        }
        return new FieldSpec(spec.path(), spec.type(), spec.defaultValue(), spec.constraints(),
                label == null ? spec.label() : label, helper == null ? spec.helper() : helper,
                spec.widget(), spec.valueSpec());
    }

    /**
     * @param path   字段完整 schema path
     * @param suffix 语言键后缀
     * @return {@code config.qz_miner.<path 末段><suffix>}
     */
    private static String languageKey(String path, String suffix) {
        if (path == null) {
            throw new IllegalArgumentException("path 不可为 null");
        }
        int dot = path.lastIndexOf('.');
        String leaf = dot < 0 ? path : path.substring(dot + 1);
        return KEY_PREFIX + leaf + suffix;
    }

    /**
     * @param resolver 语言文本解析边界
     * @param key      语言键
     * @return 命中文本；语言键缺失（原版 {@code StatCollector} 返回 key 本身）、空串或 null 一律返回 null
     */
    private static String resolveOrNull(TextResolver resolver, String key) {
        String text = resolver.resolve(key);
        if (text == null || text.isEmpty() || key.equals(text)) {
            return null;
        }
        return text;
    }

    /** 只替换 helper / label 的代理渲染器；其余字段与信号全部透传原实现。 */
    private static final class LocalizingRenderer implements FieldRenderer {

        /** 控件层：按字段类型分发的默认渲染器，本地化不参与控件选择。 */
        private final FieldRendererRegistry delegates = FieldRendererRegistry.defaultRegistry();
        private final TextResolver resolver;

        private LocalizingRenderer(TextResolver resolver) {
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
