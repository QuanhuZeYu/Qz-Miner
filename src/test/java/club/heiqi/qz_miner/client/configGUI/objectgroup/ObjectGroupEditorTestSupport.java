package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.editor.CandidateSourceValueEditorProvider;
import club.heiqi.config.ui.editor.CategorizedValueEditorProvider;
import club.heiqi.config.ui.editor.PickerCandidateSource;
import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.PickerSourceVersion;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.ValueEditorProvider;
import club.heiqi.qz_miner.client.picker.BlockPickerProvider;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.InputFrameBuilder;
import club.heiqi.uilib.ui.scene.input.RawInputEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.input.SceneMouseButton;
import club.heiqi.uilib.ui.scene.input.ScenePointerAction;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.layout.SceneLayoutEngine;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.overlay.SceneOverlayHost;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/**
 * M9 验收的 Miner 侧 headless 搭台设施（测试专用）。
 *
 * <p><b>为什么自建</b>：UILib 的 {@code FixedTextMeasurer} / {@code SceneInteractionHarness} /
 * {@code ReactiveTestProbe} 全部位于其 {@code src/test}，不发布到制品；Miner 只能拿主 jar，
 * 因此本节按同样的公开 API 自建最小等价物（文本度量桩、输入注入、overlay 布局探针、effect 计数探针）。</p>
 *
 * <p><b>面板构建探针</b>：实现未提供面板工厂计数器，本设施以「惰性候选源 + 版本桥」作为可注入观察点：
 * {@code PickerRevisionBridge} 仅在 {@code ObjectGroupMemberPane.createPanel} 内被创建（唯一来源），
 * 其 {@code tick()} 会对候选源调用 {@code onEnvironmentChanged(...)}；因此
 * 「帧时间推进后候选源收到环境推送」⇔「picker 面板已被构建」。探针强度与局限见复核报告。</p>
 */
final class ObjectGroupEditorTestSupport {

    private ObjectGroupEditorTestSupport() {
    }

    // ==================================================================
    // 确定性文本度量（Miner 侧自建；口径对齐 UILib FixedTextMeasurer 8/16）
    // ==================================================================

    static final class MiniMeasurer implements SceneTextMeasurer {
        private final int charWidth;
        private final int lineHeight;
        private int epoch;

        MiniMeasurer() {
            this(8, 16);
        }

        MiniMeasurer(int charWidth, int lineHeight) {
            this.charWidth = charWidth;
            this.lineHeight = lineHeight;
        }

        @Override
        public int measureWidth(String text, int fontSizePx) {
            return (text == null ? 0 : text.length()) * charWidth;
        }

        @Override
        public int lineHeight(int fontSizePx) {
            return lineHeight;
        }

        @Override
        public int ascent(int fontSizePx) {
            return 12;
        }

        @Override
        public int descent(int fontSizePx) {
            return 4;
        }

        @Override
        public int epoch() {
            return epoch;
        }

        @Override
        public List<String> splitLines(String text, int fontSizePx, int wrapWidth, int textMode) {
            String safe = text == null ? "" : text;
            if (wrapWidth > 0) {
                return Collections.singletonList(safe);
            }
            return java.util.Arrays.asList(safe.split("\n"));
        }
    }

    // ==================================================================
    // picker 面板构建探针
    // ==================================================================

    /** 计数候选源：只统计 UILib 侧调用，不做任何候选物化。 */
    static final class ProbeSource implements PickerCandidateSource {
        private int environmentPushes;
        private int versionReads;
        private int candidateQueries;

        int environmentPushes() {
            return environmentPushes;
        }

        int versionReads() {
            return versionReads;
        }

        int candidateQueries() {
            return candidateQueries;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public long registryRevision() {
            return 0L;
        }

        @Override
        public long nameRevision() {
            return 1L + environmentPushes;
        }

        @Override
        public long iconRevision() {
            return 1L + environmentPushes;
        }

        @Override
        public PickerSourceVersion version() {
            versionReads++;
            return new PickerSourceVersion(registryRevision(), nameRevision(), iconRevision());
        }

        @Override
        public int matchCount(PickerQuery query) {
            candidateQueries++;
            return 0;
        }

        @Override
        public List<SearchPickerData.Candidate> page(PickerQuery query, int offset, int limit) {
            candidateQueries++;
            return Collections.emptyList();
        }

        @Override
        public SearchPickerData.Candidate exact(String candidateKey) {
            candidateQueries++;
            return null;
        }

        @Override
        public List<SearchPickerCategories.Category> categories(int dimension) {
            return Collections.emptyList();
        }

        @Override
        public void onEnvironmentChanged(long nameEpoch, long resourceEpoch) {
            environmentPushes++;
        }
    }

    /**
     * 注册项探针 provider：除 {@code candidateSource()} 返回探针源外，全部委托真实
     * {@link BlockPickerProvider}（文案/编解码/图标/现值呈现保持生产口径）。
     *
     * <p>注册期 {@link Registry#register} 会快照除候选源引用以外的字段，因此候选源引用
     * （本探针唯一入口）能可靠地穿透到面板侧。</p>
     *
     * @param real  真实 provider（生产装配）
     * @param probe 计数候选源
     * @return 可注册的 provider 探针
     */
    static ValueEditorProvider probeProvider(final BlockPickerProvider real, final PickerCandidateSource probe) {
        return (ValueEditorProvider) Proxy.newProxyInstance(
                ObjectGroupEditorTestSupport.class.getClassLoader(),
                new Class<?>[] {CategorizedValueEditorProvider.class, CandidateSourceValueEditorProvider.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("candidateSource".equals(method.getName())) {
                            return probe;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    // ==================================================================
    // 场景 harness（layout + overlay 布局 + 输入注入）
    // ==================================================================

    /** 单屏 headless harness：主树 + 各 overlay root 独立布局引擎（生产帧管线的测试等价物）。 */
    static final class Harness implements AutoCloseable {
        final MiniMeasurer measurer = new MiniMeasurer();
        final SceneRuntime rt = new SceneRuntime(measurer);
        final SceneLayoutEngine engine = new SceneLayoutEngine(measurer);
        final SceneNode root;
        private final Map<SceneNode, SceneLayoutEngine> overlayEngines =
                new IdentityHashMap<SceneNode, SceneLayoutEngine>();
        private int width;
        private int height;
        private long frameNanos = 1_000_000L;

        Harness(int width, int height) {
            this.width = width;
            this.height = height;
            this.root = SceneNode.column();
            root.setFillParentWidth(true);
            root.setFillParentHeight(true);
            rt.__setViewportLogicalBox(width, height);
        }

        /** 注入宽度（§9.1-6 挡位切换的唯一输入）。 */
        void resize(int nextWidth, int nextHeight) {
            this.width = nextWidth;
            this.height = nextHeight;
            rt.__setViewportLogicalBox(nextWidth, nextHeight);
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        /**
         * 一遍帧：推进帧时间 → （布局 + flush）× N → 末次布局。
         *
         * <p>收敛循环是必要的：效果在 flush 内挂载新节点、新节点的派生又触发下一次挂载，
         * 单遍布局会让「最后一次 flush 里挂载的节点」永远没有布局盒（点击取几何即零盒）。
         * 末次布局保证所有已挂载节点都有 cachedLayout。</p>
         */
        void frame() {
            rt.__tickFrame(frameNanos);
            frameNanos += 16_000_000L;
            for (int pass = 0; pass < 8; pass++) {
                layoutAll();
                rt.flush();
            }
            layoutAll();
        }

        private void layoutAll() {
            engine.layout(root, new Constraints(width, height));
            long epoch = engine.layoutChangeEpoch();
            Set<SceneNode> active = Collections.newSetFromMap(new IdentityHashMap<SceneNode, Boolean>());
            for (SceneOverlayHost.Entry entry : rt.getOverlayHost().bottomFirst()) {
                SceneNode overlayRoot = entry.getRoot();
                active.add(overlayRoot);
                SceneLayoutEngine overlayEngine = overlayEngines.get(overlayRoot);
                if (overlayEngine == null) {
                    overlayEngine = new SceneLayoutEngine(measurer);
                    overlayEngines.put(overlayRoot, overlayEngine);
                }
                overlayEngine.layout(overlayRoot, new Constraints(width, height));
                epoch += overlayEngine.layoutChangeEpoch();
            }
            overlayEngines.keySet().retainAll(active);
            rt.__setLayoutDoneEpoch((int) epoch);
        }

        /** 面板/视图根列表（bottom-first：编辑器在前，picker 在后）。 */
        List<SceneNode> overlayRoots() {
            List<SceneNode> roots = new ArrayList<SceneNode>();
            for (SceneOverlayHost.Entry entry : rt.getOverlayHost().bottomFirst()) {
                roots.add(entry.getRoot());
            }
            return roots;
        }

        int overlayCount() {
            return rt.getOverlayHost().size();
        }

        /** 编辑器视图根（栈底 overlay），无 overlay 时 null。 */
        SceneNode editorRoot() {
            List<SceneNode> roots = overlayRoots();
            return roots.isEmpty() ? null : roots.get(0);
        }

        /** 栈顶 overlay root（picker 打开时为 picker），无 overlay 时 null。 */
        SceneNode topRoot() {
            List<SceneNode> roots = overlayRoots();
            return roots.isEmpty() ? null : roots.get(roots.size() - 1);
        }

        /** 点击节点中心（DOWN/UP 分帧 + flush + 一帧布局）；节点尚无布局盒时先补跑一帧。 */
        void click(SceneNode node) {
            AnchorRect box = boxOrNull(node);
            for (int retry = 0; retry < 3 && box == null; retry++) {
                frame();
                box = boxOrNull(node);
            }
            if (box == null) {
                throw new IllegalStateException("节点未布局或零尺寸，无法点击: "
                        + "text='" + node.getText() + "' cached=" + node.getCachedLayout()
                        + " parent=" + node.__getParent());
            }
            clickAt(box.getX() + box.getWidth() / 2, box.getY() + box.getHeight() / 2);
        }

        /** 节点绝对盒；零尺寸或无布局时返回 null。 */
        AnchorRect boxOrNull(SceneNode node) {
            AnchorRect box = SceneGeometry.absoluteBox(node, 0, 0);
            return box.getWidth() > 0 && box.getHeight() > 0 ? box : null;
        }

        void clickAt(int x, int y) {
            InputFrameBuilder down = new InputFrameBuilder(x, y);
            down.push(RawInputEvent.ofPointer(ScenePointerAction.BUTTON_DOWN, x, y, SceneMouseButton.LEFT,
                    0, 0, 0, false, false, false, false, frameNanos));
            rt.route(root, down.drainFrame(), 0, 0);
            InputFrameBuilder up = new InputFrameBuilder(x, y);
            up.push(RawInputEvent.ofPointer(ScenePointerAction.BUTTON_UP, x, y, SceneMouseButton.LEFT,
                    0, 0, 0, false, false, false, false, frameNanos + 1L));
            rt.route(root, up.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        /** 注入按键 PRESSED（焦点由调用方/前序交互建立）。 */
        void pressKey(SceneKey key) {
            pressKey(key, false);
        }

        /**
         * 注入带 Shift 修饰的按键 PRESSED。
         *
         * <p>{@code RawInputEvent.ofKey} 的形参序是 control → shift → alt → meta；此处必须把
         * {@code shift} 放在**第二个**修饰位。此前该重载把它写进第一位（即 controlDown），
         * 使「Shift+点击/Shift+按键」类断言实际注入 Ctrl——因当时无调用点而未暴露观感问题，
         * 但写出来的断言会验错东西（不可证伪的断言即负债）。</p>
         */
        void pressKey(SceneKey key, boolean shift) {
            InputFrameBuilder frameBuilder = new InputFrameBuilder(0, 0);
            frameBuilder.push(RawInputEvent.ofKey(key, SceneKeyAction.PRESSED,
                    false, shift, false, false, 0, 0, frameNanos));
            rt.route(root, frameBuilder.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        SceneNode focused() {
            return rt.getFocusedNode();
        }

        @Override
        public void close() {
            rt.dispose();
        }
    }

    // ==================================================================
    // 树遍历工具
    // ==================================================================

    static List<SceneNode> descendants(SceneNode node) {
        List<SceneNode> out = new ArrayList<SceneNode>();
        collect(node, out);
        return out;
    }

    private static void collect(SceneNode node, List<SceneNode> out) {
        out.add(node);
        for (SceneNode child : node.__getChildren()) {
            collect(child, out);
        }
    }

    static SceneNode findText(SceneNode node, String text) {
        for (SceneNode candidate : descendants(node)) {
            if (text.equals(candidate.getText())) {
                return candidate;
            }
        }
        return null;
    }

    static List<SceneNode> findAllText(SceneNode node, String text) {
        List<SceneNode> out = new ArrayList<SceneNode>();
        for (SceneNode candidate : descendants(node)) {
            if (text.equals(candidate.getText())) {
                out.add(candidate);
            }
        }
        return out;
    }

    static boolean hasText(SceneNode node, String text) {
        return findText(node, text) != null;
    }

    static List<String> texts(SceneNode node) {
        List<String> out = new ArrayList<String>();
        for (SceneNode candidate : descendants(node)) {
            String value = candidate.getText();
            if (value != null && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    static int countScrollable(SceneNode node) {
        int count = 0;
        for (SceneNode candidate : descendants(node)) {
            if (candidate.isScrollable()) {
                count++;
            }
        }
        return count;
    }

    static int nodeCount(SceneNode node) {
        return descendants(node).size();
    }

    /** 诊断用树转储：仅列出有文本/可滚动的节点及其绝对盒。 */
    static String dump(SceneNode node) {
        StringBuilder out = new StringBuilder();
        for (SceneNode candidate : descendants(node)) {
            String text = candidate.getText();
            if ((text != null && !text.isEmpty()) || candidate.isScrollable()) {
                AnchorRect box = SceneGeometry.absoluteBox(candidate, 0, 0);
                out.append('[').append(box.getX()).append(',').append(box.getY()).append(' ')
                        .append(box.getWidth()).append('x').append(box.getHeight()).append(']')
                        .append(candidate.isScrollable() ? " scroll" : "")
                        .append(" text='").append(text).append("'").append('\n');
            }
        }
        return out.toString();
    }

    /** 该节点是否被删除控件（含删除文案的按钮/文本）包住——用于「初始焦点不在删除控件上」判定。 */
    static boolean insideText(SceneNode node, String text) {
        SceneNode current = node;
        while (current != null) {
            if (text.equals(current.getText())) {
                return true;
            }
            current = current.__getParent();
        }
        return false;
    }

    // ==================================================================
    // effect 计数探针（UILib ReactiveTestProbe 不发布 ⇒ 反射转发 package-private 计数）
    // ==================================================================

    /**
     * 当前已注册（未 dispose）的 effect 数；探针不可用时返回 -1。
     *
     * @return effect 数，或 -1 表示探针不可用
     */
    static int effectCount() {
        try {
            Method method = ReactiveScheduler.class.getDeclaredMethod("registeredEffectCount");
            method.setAccessible(true);
            Object value = method.invoke(ReactiveScheduler.get());
            return value instanceof Integer ? ((Integer) value).intValue() : -1;
        } catch (Exception probeFailure) {
            return -1;
        }
    }

    // ==================================================================
    // 配置草稿 / 适配器 / 注册表
    // ==================================================================

    /**
     * 测试夹具：历史上的 3 组 vanilla 对象组。
     *
     * <p>生产默认 {@code client.objectGroups} 现为两组（「红石矿石」+「暮色森林极光方块」，modes/members
     * 均非空）且会随 issue 增补，而 M9 的多组行为用例（移动/复制/重命名身份/列表导航/窄挡下钻）需要 ≥3 组
     * 才有判别力。这些用例不再借用生产默认值，改用本夹具显式提供；夹具经 {@link #bootstrap(File)} 装到
     * authority 层，与真机「用户已有 3 组配置」的启动态等价（草稿 base/current 都是夹具 ⇒ 初始不脏）。</p>
     *
     * @return 3 组对象组值（modes 全空，沿用重构前的历史默认形状；现生产默认的 modes 已非空）
     */
    static List<Map<String, Object>> threeGroupFixture() {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(fixtureGroup("vanilla_logs", "minecraft:log@*", "minecraft:log2@*"));
        groups.add(fixtureGroup("vanilla_hay", "minecraft:hay_block@[0,4,8]"));
        groups.add(fixtureGroup("vanilla_redstone",
                "minecraft:redstone_ore@*", "minecraft:lit_redstone_ore@*"));
        return groups;
    }

    private static Map<String, Object> fixtureGroup(String id, String... members) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", new ArrayList<String>());
        group.put("members", new ArrayList<String>(java.util.Arrays.asList(members)));
        return group;
    }

    /**
     * 启动测试 authority 并装入 {@link #threeGroupFixture()}。
     *
     * <p>夹具走真实 save 事务写入 authority 与磁盘：失败即抛（不静默退回生产默认，避免用例在
     * 错误前提下继续跑）。</p>
     *
     * @param configDir 临时配置目录
     * @return 已装夹具的 manager
     */
    static ConfigManager bootstrap(File configDir) {
        ConfigBootstrap.resetForTests();
        ConfigManager manager = ConfigBootstrap.bootstrap(configDir, null);
        DraftBuffer seed = manager.openDraft();
        seed.setDraft(ObjectGroupEditorState.PATH, threeGroupFixture());
        SaveOutcome outcome = manager.save(seed);
        if (!outcome.isSuccess()) {
            throw new IllegalStateException("3 组测试夹具未能提交为 authority: "
                    + outcome.status() + " " + outcome.errorMessage());
        }
        return manager;
    }

    static FieldSpec objectGroupsSpec() {
        return QzMinerConfigSchema.create().field(ObjectGroupEditorState.PATH);
    }

    static DraftSignalAdapter adapterOf(DraftBuffer draft) {
        return new DraftSignalAdapter(null, draft);
    }

    /** 注册表 + 面板构建探针 + 渲染器（真实 M1）。 */
    static final class RendererFixture {
        final DraftBuffer draft;
        final DraftSignalAdapter adapter;
        final FieldSpec spec;
        final ProbeSource probe = new ProbeSource();
        final Registry registry = new Registry();
        final ObjectGroupEditorFieldRenderer renderer;

        RendererFixture(ConfigManager manager) {
            this.draft = manager.openDraft();
            this.adapter = adapterOf(draft);
            this.spec = objectGroupsSpec();
            registry.register(probeProvider(new BlockPickerProvider(), probe));
            registry.freeze();
            final Registry frozen = registry;
            this.renderer = new ObjectGroupEditorFieldRenderer(new Supplier<Registry>() {
                @Override
                public Registry get() {
                    return frozen;
                }
            });
        }

        void dispose() {
            adapter.dispose();
        }
    }

    /** 深拷贝（草稿不变性断言用）。 */
    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new java.util.LinkedHashMap<Object, Object>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        }
        return value;
    }
}
