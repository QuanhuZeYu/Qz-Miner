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
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
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
 * 编辑器状态在同一 adapter 上重建为与默认组一一对应且零冲突/零错误/已生效。</p>
 */
public class ConfigRestoreDefaultsEndToEndTest {

    private static final String PATH = ObjectGroupEditorState.PATH;


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
    public void restoreDefaultsOnEditedDraftReturnsToShippedDefaultGroups() throws Exception {
        FieldSpec spec = QzMinerConfigSchema.create().field(PATH);
        List<Map<String, Object>> shipped = QzMinerConfigDefaults.objectGroups();
        Assert.assertEquals("开屏草稿必须就是出厂默认",
                QzMinerConfigDefaults.objectGroups(), adapter.draft().getDraft(PATH));

        // ---- 1) 真实编辑事务改脏草稿：删一组 → 新建组 → 改 id → 加成员 → 选模式 ----
        ObjectGroupEditorState state = new ObjectGroupEditorState(spec, adapter);
        Assert.assertEquals("开屏视图必须与出厂默认组一一对应", shipped.size(), state.views().size());
        long defaultKey = state.views().get(0).key();
        Assert.assertTrue("删除默认组必须被接受", state.removeGroup(defaultKey).accepted());
        settle();
        Assert.assertEquals("删除必须只移除一行", shipped.size() - 1, state.views().size());

        Assert.assertTrue(state.addGroup().accepted());
        settle();
        long customKey = state.views().get(state.views().size() - 1).key();
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
        List<ObjectGroup> editedGroups = editedParsed.rules().groups();
        Assert.assertEquals("前置条件：新建组必须是最后一行", "自定义组",
                editedGroups.get(editedGroups.size() - 1).id());
        List<String> editedIds = new ArrayList<String>();
        for (ObjectGroup group : editedGroups) {
            editedIds.add(group.id());
        }
        Assert.assertFalse("前置条件：被删的默认组不得残留在草稿里",
                editedIds.contains(String.valueOf(shipped.get(0).get("id"))));

        // ---- 2) 走真实「恢复默认」：ConfigScreen.restoreDefaults()（空策略 ⇒ 全字段 resetFieldToDefault） ----
        restoreDefaultsOn(screen);
        settle();

        // ---- 3) 断言草稿 == 新默认（顺序/modes/members 逐项全等） ----
        Object restored = adapter.draft().getDraft(PATH);
        Assert.assertEquals("恢复默认必须回到 QzMinerConfigDefaults.objectGroups()（唯一真源）",
                QzMinerConfigDefaults.objectGroups(), restored);
        List<?> restoredGroups = (List<?>) restored;
        Assert.assertEquals("恢复后的组数必须与出厂默认一致", shipped.size(), restoredGroups.size());
        for (Object rawGroup : restoredGroups) {
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            Assert.assertEquals("组三键必须 id/modes/members 且保序",
                    Arrays.asList("id", "modes", "members"), new ArrayList<Object>(group.keySet()));
            Assert.assertFalse("恢复后的组 modes 不得为空: " + group.get("id"),
                    ((List<?>) group.get("modes")).isEmpty());
            Assert.assertFalse("恢复后的组 members 不得为空: " + group.get("id"),
                    ((List<?>) group.get("members")).isEmpty());
        }

        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(restored);
        Assert.assertTrue("恢复后的草稿必须仍是有效规则集: " + parsed.error(), parsed.isValid());
        for (ObjectGroup restoredGroup : parsed.rules().groups()) {
            Assert.assertNotEquals("恢复后的组不得是未生效态（模式位非 0）: " + restoredGroup.id(),
                    0L, restoredGroup.modeMask());
        }
        Assert.assertNull("恢复后的草稿不得有内置校验错误", adapter.draft().error(PATH));

        // ---- 4) 提交级证据：恢复后的草稿能通过完整 save 事务（含 Miner 自定义 DraftValidator） ----
        Assert.assertTrue("恢复默认后的草稿必须可提交", manager.save(adapter.draft()).isSuccess());
        Assert.assertEquals("提交后 authority 也必须是同一默认",
                QzMinerConfigDefaults.objectGroups(), manager.authority().get(PATH));
        ObjectGroupRuleSet committed = ConfigBootstrap.currentValidatedSnapshot().objectGroups;
        Assert.assertEquals("提交快照组数必须与出厂默认一致", shipped.size(), committed.groups().size());
        for (ObjectGroup committedGroup : committed.groups()) {
            Assert.assertNotEquals("提交快照的每个组必须已生效（模式位非 0）: " + committedGroup.id(),
                    0L, committedGroup.modeMask());
        }

        // ---- 5) 编辑器状态在同一 adapter 上重建：与默认组一一对应、零冲突/零错误、已生效、仍可编辑 ----
        ObjectGroupEditorState after = new ObjectGroupEditorState(spec, adapter);
        Assert.assertEquals("恢复默认后编辑器视图必须与出厂默认一一对应",
                shipped.size(), after.summary().groupCount());
        Assert.assertEquals("恢复后不得有冲突组", 0, after.summary().conflictCount());
        Assert.assertEquals("恢复后不得有未生效组", 0, after.summary().inactiveCount());
        Assert.assertEquals("恢复后不得有未完成组", 0, after.summary().incompleteCount());
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
