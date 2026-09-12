package club.heiqi.qz_miner.config;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.ConfigScreen;
import club.heiqi.config.ui.ConfigUI;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorState;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;

/**
 * 配置页「恢复默认」的端到端测试（真实 UILib {@link ConfigScreen} + Miner 真实装配形状）。
 *
 * <p><b>为什么这样取真机路径</b>：{@code ConfigScreen.__restoreDefaults()} / {@code __getAdapter()}
 * 是 UILib 的<b>包内</b>测试探针（package-private），而 Miner 测试源码树只有 {@code qz_miner} 包，
 * 同一包内直调不可达。本测试因此：</p>
 * <ol>
 *   <li>用与生产 {@code QzMinerConfigGUI.buildSurface()} 完全同形的 5 参
 *       {@link ConfigUI#buildScreen} 装配真实 ConfigScreen——同样的 {@code registry} 定制 lambda 与
 *       <b>空恢复策略</b>（{@code policy -> { }}，Miner 未定制任何跳过后不恢复的字段），只把平台输入源
 *       换成官方文档标注的 headless {@code null}；</li>
 *   <li>用反射调用 UILib 自己的测试探针 {@code __restoreDefaults()}（即真实按钮处理逻辑
 *       {@code restoreDefaults()}：遍历 schema 全字段 → {@code adapter.resetFieldToDefault(path)}）。
 *       反射只用于触发探针，被测逻辑 100% 是生产实现。</li>
 * </ol>
 *
 * <p>端到端链路：真实编辑事务改脏草稿 → 真实恢复默认 → 断言草稿逐项等于
 * {@link QzMinerConfigDefaults#objectGroups()}（顺序/modes/members 全等）→ 该草稿可提交 →
 * 编辑器状态在同一 adapter 上重建为单组且零冲突/零错误/已生效。</p>
 */
public class ConfigRestoreDefaultsEndToEndTest {

    private static final String PATH = ObjectGroupEditorState.PATH;

    /** chain_base|chain_ore|area_same_block|area_ore = 27（hex 0x1b，Python 验算）。 */
    private static final long EXPECTED_MODE_MASK = 27L;

    private static final List<String> EXPECTED_MODES = Arrays.asList(
            ObjectGroupMode.CHAIN_BASE,
            ObjectGroupMode.CHAIN_ORE,
            ObjectGroupMode.AREA_SAME_BLOCK,
            ObjectGroupMode.AREA_ORE);

    private static final List<String> EXPECTED_MEMBERS = Arrays.asList(
            "minecraft:redstone_ore@*",
            "minecraft:lit_redstone_ore@*",
            "etfuturum:deepslate_redstone_ore@*",
            "etfuturum:deepslate_lit_redstone_ore@*");

    private File tempDir;
    private ConfigManager manager;
    private ConfigScreen screen;
    private DraftSignalAdapter adapter;

    @Before
    public void setUp() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory("qz-miner-restore-e2e-").toFile();
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        manager = ConfigBootstrap.bootstrap(tempDir, null);
        // 与 QzMinerConfigGUI.buildSurface() 同形：空 renderer 定制 + 空恢复策略 + 空 editor registry。
        screen = ConfigUI.buildScreen(manager, null, registry -> { }, policy -> { }, editors -> { });
        adapter = screenAdapter(screen);
    }

    @After
    public void tearDown() {
        if (screen != null) {
            screen.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    @Test
    public void restoreDefaultsOnEditedDraftReturnsToShippedSingleGroup() throws Exception {
        FieldSpec spec = QzMinerConfigSchema.create().field(PATH);
        Assert.assertEquals("开屏草稿必须就是出厂默认",
                QzMinerConfigDefaults.objectGroups(), adapter.draft().getDraft(PATH));

        // ---- 1) 真实编辑事务改脏草稿：删默认组 → 新建组 → 改 id → 加成员 → 选模式 ----
        ObjectGroupEditorState state = new ObjectGroupEditorState(spec, adapter);
        long defaultKey = state.views().get(0).key();
        Assert.assertTrue("删除默认组必须被接受", state.removeGroup(defaultKey).accepted());
        settle();
        Assert.assertEquals("默认组必须已从草稿删除", 0, state.views().size());

        Assert.assertTrue(state.addGroup().accepted());
        settle();
        long customKey = state.views().get(0).key();
        Assert.assertTrue(state.renameGroup(customKey, "自定义组").accepted());
        settle();
        Assert.assertTrue(state.addMember(customKey, "minecraft:stone@*").accepted());
        // 每个编辑命令后必须收敛一帧：state 的读路径走 adapter 的 draft 镜像 signal，
        // 与真机「一帧一次交互」时序一致（否则下一条命令会基于上一帧的旧列表写回）。
        settle();
        Assert.assertTrue(state.toggleGroupMode(customKey, ObjectGroupMode.CHAIN_ORE).accepted());
        settle();

        Object edited = adapter.draft().getDraft(PATH);
        Assert.assertNotEquals("前置条件：草稿必须已被改脏", QzMinerConfigDefaults.objectGroups(), edited);
        ObjectGroupParser.ParseResult editedParsed = ObjectGroupParser.parse(edited);
        Assert.assertTrue("前置条件：编辑后的草稿是合法草稿: " + editedParsed.error(), editedParsed.isValid());
        Assert.assertEquals("前置条件：默认组已被替换", "自定义组", editedParsed.rules().groups().get(0).id());

        // ---- 2) 走真实「恢复默认」：ConfigScreen.restoreDefaults()（空策略 ⇒ 全字段 resetFieldToDefault） ----
        restoreDefaultsOn(screen);
        settle();

        // ---- 3) 断言草稿 == 新默认（顺序/modes/members 逐项全等） ----
        Object restored = adapter.draft().getDraft(PATH);
        Assert.assertEquals("恢复默认必须回到 QzMinerConfigDefaults.objectGroups()（唯一真源）",
                QzMinerConfigDefaults.objectGroups(), restored);
        List<?> restoredGroups = (List<?>) restored;
        Assert.assertEquals("恢复后恰好 1 组", 1, restoredGroups.size());
        Map<?, ?> group = (Map<?, ?>) restoredGroups.get(0);
        Assert.assertEquals("组三键必须 id/modes/members 且保序",
                Arrays.asList("id", "modes", "members"), new ArrayList<Object>(group.keySet()));
        Assert.assertEquals("红石矿石", group.get("id"));
        Assert.assertEquals("modes 必须逐项保序", EXPECTED_MODES, group.get("modes"));
        Assert.assertEquals("members 必须逐项保序", EXPECTED_MEMBERS, group.get("members"));

        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(restored);
        Assert.assertTrue("恢复后的草稿必须仍是有效规则集: " + parsed.error(), parsed.isValid());
        Assert.assertEquals("模式掩码必须是四模式全开", EXPECTED_MODE_MASK,
                parsed.rules().groups().get(0).modeMask());
        Assert.assertNull("恢复后的草稿不得有内置校验错误", adapter.draft().error(PATH));

        // ---- 4) 提交级证据：恢复后的草稿能通过完整 save 事务（含 Miner 自定义 DraftValidator） ----
        Assert.assertTrue("恢复默认后的草稿必须可提交", manager.save(adapter.draft()).isSuccess());
        Assert.assertEquals("提交后 authority 也必须是同一默认",
                QzMinerConfigDefaults.objectGroups(), manager.authority().get(PATH));
        Assert.assertEquals("提交后提交快照已生效（模式掩码非 0）", EXPECTED_MODE_MASK,
                ConfigBootstrap.currentValidatedSnapshot().objectGroups.groups().get(0).modeMask());

        // ---- 5) 编辑器状态在同一 adapter 上重建：单组、零冲突/零错误、已生效、仍可编辑 ----
        ObjectGroupEditorState after = new ObjectGroupEditorState(spec, adapter);
        Assert.assertEquals(1, after.summary().groupCount());
        Assert.assertEquals(4, after.summary().memberCount());
        Assert.assertEquals("恢复后不得有冲突组", 0, after.summary().conflictCount());
        Assert.assertEquals("恢复后不得有未生效组", 0, after.summary().inactiveCount());
        Assert.assertEquals("恢复后不得有未完成组", 0, after.summary().incompleteCount());
        Assert.assertEquals("红石矿石", after.views().get(0).id());
        Assert.assertTrue("恢复后视图仍必须可编辑",
                after.addMember(after.views().get(0).key(), "minecraft:gold_ore@0").accepted());
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 收敛一帧：把适配器内待写入的 signal 落到最新值。 */
    private static void settle() {
        ReactiveScheduler.get().flush();
    }

    /**
     * 取真实 ConfigScreen 的草稿适配器（UILib 包内探针 {@code __getAdapter()}）。
     *
     * @param target 真实 screen
     * @return 该 screen 的适配器
     * @throws Exception 探针不可达（测试必须失败，不得静默跳过）
     */
    private static DraftSignalAdapter screenAdapter(ConfigScreen target) throws Exception {
        Method probe = ConfigScreen.class.getDeclaredMethod("__getAdapter");
        probe.setAccessible(true);
        return (DraftSignalAdapter) probe.invoke(target);
    }

    /**
     * 触发真实「恢复默认」（UILib 包内探针 {@code __restoreDefaults()} → 私有 {@code restoreDefaults()}）。
     *
     * @param target 真实 screen
     * @throws Exception 探针不可达（测试必须失败，不得静默跳过）
     */
    private static void restoreDefaultsOn(ConfigScreen target) throws Exception {
        Method probe = ConfigScreen.class.getDeclaredMethod("__restoreDefaults");
        probe.setAccessible(true);
        probe.invoke(target);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
