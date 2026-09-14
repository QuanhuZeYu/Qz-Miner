package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.FieldRenderSupport;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.config.ui.field.FieldShellBinder;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 预览颜色字段渲染器：把四个 {@code 0xRRGGBB} 颜色键渲染成 <b>HEX 文本输入框</b>
 * （{@code #RRGGBB}），替代改造前的纯十进制数字框（如显示 {@code 4253439}）。
 *
 * <p>受覆盖的路径集合以 {@link #paths()} 为唯一事实源；控件外壳、错误/脏态渲染、主题语义色
 * 全部复用 UILib 既有通道（{@link FieldShellBinder} + {@link SceneTextInput}），本类不新增控件实现。</p>
 *
 * <h3>为什么必须用文本类型</h3>
 * <p>{@link SceneInputType#NUMBER} 的字符过滤只放行 {@code 0-9 . - + e E}，会吃掉 {@code #}，
 * 故取 {@link SceneInputType#TEXT}，字符合法性由 {@link HexColorCodec} 判读。</p>
 *
 * <h3>编辑期原文（本类的技术核心）</h3>
 * <p>{@link SceneTextInput} 是<b>完全受控</b>控件：文本真值只由外部 value 信号派生、控件不缓存，
 * 键盘输入只经 {@code onChange} 上抛。若照搬「输入即解析回写 value」，用户输入过程中的中间态
 * 会被解析失败或规范化吃掉（敲 {@code #} 或 {@code #40E6F} 时「敲了没反应」；敲十进制 {@code 4253439}
 * 时首字符 {@code 4} 就被规范成 {@code #000004}，后续输入全废）。因此本渲染器自持一个
 * <b>编辑期原文信号</b> {@code editing}：</p>
 * <ul>
 *   <li>输入一律先写 {@code editing}，显示文本 = 编辑期原文（非 null 时优先），否则从草稿值
 *       {@link #displayText(Object)} 派生——<b>颜色真值始终只有草稿一个</b>，本类不存第二份颜色状态；</li>
 *   <li>文本构成合法颜色（{@link HexColorCodec#parse(String)} 非 null）才回写 Double 值；</li>
 *   <li>非法文本按原文写进草稿，走 DraftBuffer 校验报错路径（非数字 → 报错并锁保存；数字形态
 *       → 由 UILib 既有 NUMBER 归一化/范围校验裁决，与改造前数字框一致），不静默改值；</li>
 *   <li>失焦清空 {@code editing}：显示回到「从草稿值派生」。合法值此时规范化为 {@code #RRGGBB}
 *       （如输入 {@code 40e6ff} 或 {@code 4253439} 落成 {@code #40E6FF}）；非法原文已写进草稿，
 *       显示与错误提示都保持用户输入，不被静默改写。</li>
 * </ul>
 *
 * <h3>注册方式</h3>
 * <p>只挂控件层：{@link #install(FieldRendererRegistry)} 把四个路径覆盖进「控件注册表」，
 * 该注册表再作为 {@link PreviewConfigTooltips#install(FieldRendererRegistry, FieldRendererRegistry)}
 * 的委托层——本地化代理仍按路径命中本渲染器，于是这两个 path 装饰（本地化 helper + HEX 控件）
 * 各自独立叠加，不靠注册顺序取胜。</p>
 */
public final class PreviewColorFieldRenderer implements FieldRenderer {

    /** 受覆盖的字段完整 path（唯一事实源；与 schema 键名一一对应）。 */
    private static final String[] PATHS = {
            "client.clientPreviewColorPrimary",
            "client.clientPreviewColorSecondary",
            "client.clientPreviewColorRemote",
            "client.clientPreviewColorTruncated"};

    /** 占位文本：格式说明（语言中立，不入语言表）。 */
    private static final String PLACEHOLDER = "#RRGGBB";

    /** 纯静态工厂语义，但实现接口需实例化；无实例字段 */
    public PreviewColorFieldRenderer() {
    }

    /** @return 受本渲染器覆盖的字段完整 path（副本，稳定顺序） */
    public static String[] paths() {
        return PATHS.clone();
    }

    /**
     * 把四个颜色键注册为 HEX 控件（path 覆盖，优先级高于按类型的默认渲染器）。
     *
     * @param registry 控件层字段渲染器注册表
     */
    public static void install(FieldRendererRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry 不可为 null");
        }
        FieldRenderer renderer = new PreviewColorFieldRenderer();
        for (String path : PATHS) {
            registry.registerPath(path, renderer);
        }
    }

    @Override
    public SceneNode render(SceneRuntime rt, FieldSpec spec, DraftSignalAdapter adapter) {
        final String path = spec.path();
        final ReadableSignal<Object> draftSignal = adapter.draftSignal(path);
        // 编辑期原文：null = 不在编辑，显示回到从草稿值派生的形态（单真源）
        final Signal<String> editing = Signal.<String>create(null);
        ReadableSignal<String> display = Computed.create(() -> {
            String raw = editing.get();
            return raw != null ? raw : displayText(draftSignal.get());
        });

        SceneTextInput.Props props = SceneTextInput.Props.builder(display)
                // NUMBER 过滤会吃掉 '#'（SceneTextInputPrimitive 数字白名单），颜色语法自带 '#' ⇒ 文本型
                .inputType(SceneInputType.TEXT)
                .placeholder(PLACEHOLDER)
                .onChange(next -> applyEdit(adapter, path, editing, next))
                .build();

        return FieldShellBinder.build(rt, spec, adapter, () -> mountInput(rt, props, editing));
    }

    /** 构建控件根并挂失焦处理（清编辑期原文 ⇒ 显示回落到从草稿值派生）。 */
    private static SceneNode mountInput(SceneRuntime rt, SceneTextInput.Props props, Signal<String> editing) {
        SceneNode input = SceneTextInput.create(rt, props).get();
        rt.bind(rt.interactionState(input).focused(), focused -> {
            if (!Boolean.TRUE.equals(focused)) {
                editing.set(null);
            }
        });
        return input;
    }

    /**
     * 输入落点：编辑期原文照收；只有文本构成合法颜色才回写值，非法原文交给 DraftBuffer 校验。
     *
     * @param adapter 草稿适配器（唯一提交点）
     * @param path    字段全路径
     * @param editing 编辑期原文信号
     * @param next    控件上抛的期望新文本
     */
    private static void applyEdit(DraftSignalAdapter adapter, String path, Signal<String> editing, String next) {
        editing.set(next);
        Integer rgb = HexColorCodec.parse(next);
        if (rgb != null) {
            adapter.onFieldEdit(path, Double.valueOf(rgb.intValue()));
        } else {
            adapter.onFieldEdit(path, next);
        }
    }

    /**
     * 显示文本：合法颜色 → {@code #RRGGBB}；其余原样透出，绝不显示成一个并不存在的颜色。
     *
     * <p>草稿里可能是：合法 Double（用户输入或默认值）、用户敲的非法原文（String）、
     * 或越界/非整数数字（理论上被校验拦下，此处仍如实显示十进制原文而非环绕后的假颜色）。</p>
     *
     * @param value 草稿值
     * @return 显示文本（null → 空串）
     */
    static String displayText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && number >= 0 && number <= HexColorCodec.MAX_RGB) {
                return HexColorCodec.format((int) number);
            }
            return FieldRenderSupport.formatReadout(number);
        }
        return String.valueOf(value);
    }
}
