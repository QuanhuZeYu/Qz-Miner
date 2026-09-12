package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.StructuredListModel;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;

/**
 * M9：{@link ObjectGroupEditorState}（M6）的 headless 单测 —— §9.1-2 / -4 / -5 / -8 / -9 与 §5.5-3 边界。
 *
 * <p>不建场景树：直接以真实 {@link DraftBuffer} + {@link DraftSignalAdapter} 驱动编辑事务。
 * 注意 signal 写入是帧末批处理（{@code ReactiveScheduler}），故每次编辑后用 {@link #settle()}
 * 收敛一帧再断言——与真机「一次点击 = 一帧」的观测时序一致。</p>
 */
public class ObjectGroupEditorStateTest {

    private static final String PATH = ObjectGroupEditorState.PATH;

    private File tempDir;
    private ConfigManager manager;
    private DraftSignalAdapter adapter;
    private ObjectGroupEditorState state;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-objectgroup-state-").toFile();
        manager = ObjectGroupEditorTestSupport.bootstrap(tempDir);
        installDraft(null);
    }

    @After
    public void tearDown() {
        if (adapter != null) {
            adapter.dispose();
        }
        ConfigBootstrap.resetForTests();
        delete(tempDir);
    }

    /** 以给定草稿值重建 adapter 与 state（null = 使用 authority 夹具 3 组）。 */
    private void installDraft(Object draftValue) {
        if (adapter != null) {
            adapter.dispose();
        }
        DraftBuffer draft = manager.openDraft();
        if (draftValue != null) {
            draft.setDraft(PATH, draftValue);
        }
        adapter = ObjectGroupEditorTestSupport.adapterOf(draft);
        state = new ObjectGroupEditorState(ObjectGroupEditorTestSupport.objectGroupsSpec(), adapter);
    }

    /** 收敛一帧：把 command 内所有待写入 signal/Computed 落到最新值。 */
    private static void settle() {
        ReactiveScheduler.get().flush();
    }

    // ==================================================================
    // §9.1-2 摘要正确性（四元组 + 与 adapter 的 dirty/error 同步）
    // ==================================================================

    @Test
    public void summaryMatchesEmptyDefaultConflictAndIncompleteDrafts() {
        // 0 组
        installDraft(new ArrayList<Object>());
        Assert.assertEquals(0, state.summary().groupCount());
        Assert.assertEquals(0, state.summary().memberCount());
        Assert.assertEquals("0 组时不显示任何问题计数",
                ClientI18n.tr("config.qz_miner.object_group.summary", Integer.valueOf(0), Integer.valueOf(0)),
                ObjectGroupEditorFieldRenderer.summaryTextOf(state));

        // 夹具 3 组 / 5 成员 / 3 组未生效（夹具 modes 全空）
        installDraft(ObjectGroupEditorTestSupport.threeGroupFixture());
        Assert.assertEquals(3, state.summary().groupCount());
        Assert.assertEquals(5, state.summary().memberCount());
        Assert.assertEquals(0, state.summary().conflictCount());
        Assert.assertEquals(3, state.summary().inactiveCount());
        Assert.assertEquals(0, state.summary().incompleteCount());
        String expectedDefault = ClientI18n.tr("config.qz_miner.object_group.summary",
                Integer.valueOf(3), Integer.valueOf(5))
                + " · " + ClientI18n.tr("config.qz_miner.object_group.summary.inactive", Integer.valueOf(3));
        Assert.assertEquals("夹具 3 组摘要与单一真值 helper 口径一致", expectedDefault,
                ObjectGroupEditorFieldRenderer.summaryTextOf(state));

        // 含冲突：同 mode + selector 相交 ⇒ 解析器真源报两行
        installDraft(groups(
                group("a", Arrays.asList("chain_base"), "minecraft:log@*"),
                group("b", Arrays.asList("chain_base"), "minecraft:log@0")));
        Assert.assertEquals(2, state.summary().groupCount());
        Assert.assertEquals(2, state.summary().memberCount());
        Assert.assertEquals("冲突组数取自 ObjectGroupParser 真源", 2, state.summary().conflictCount());
        Assert.assertEquals(0, state.summary().inactiveCount());
        Assert.assertEquals(0, state.summary().incompleteCount());
        String expectedConflict = ClientI18n.tr("config.qz_miner.object_group.summary",
                Integer.valueOf(2), Integer.valueOf(2))
                + " · " + ClientI18n.tr("config.qz_miner.object_group.summary.conflict", Integer.valueOf(2));
        Assert.assertEquals(expectedConflict, ObjectGroupEditorFieldRenderer.summaryTextOf(state));

        // 含未生效 + 未完成
        installDraft(groups(
                group("a", new ArrayList<String>(), "minecraft:log@*"),
                group("b", Arrays.asList("chain_base"))));
        Assert.assertEquals(2, state.summary().groupCount());
        Assert.assertEquals(1, state.summary().memberCount());
        Assert.assertEquals(1, state.summary().inactiveCount());
        Assert.assertEquals(1, state.summary().incompleteCount());
        Assert.assertEquals(0, state.summary().conflictCount());
    }

    @Test
    public void dirtyAndErrorStayOwnedByAdapter() {
        Assert.assertFalse("初始草稿不脏", Boolean.TRUE.equals(adapter.dirtySignal(PATH).get()));
        String errorBefore = adapter.errorSignal(PATH).get();

        long key = state.views().get(0).key();
        Assert.assertTrue(state.renameGroup(key, "vanilla_logs_renamed").accepted());
        settle();

        Assert.assertTrue("编辑后 dirty 由 adapter 派生", Boolean.TRUE.equals(adapter.dirtySignal(PATH).get()));
        Assert.assertEquals("未提交校验前 adapter 字段错误保持原值（UI 不复刻第二份校验真值）",
                errorBefore, adapter.errorSignal(PATH).get());
        Assert.assertTrue("合法草稿的解析器真源应有效", state.parseResult().isValid());
    }

    // ==================================================================
    // §9.1-4 编辑事务：三键形状、顺序保持、本地守卫
    // ==================================================================

    @Test
    public void editTransactionsKeepThreeKeyShapeAndOrder() {
        Assert.assertEquals(Arrays.asList("vanilla_logs", "vanilla_hay", "vanilla_redstone"), ids());

        long firstKey = state.views().get(0).key();
        Assert.assertTrue(state.renameGroup(firstKey, "renamed").accepted());
        settle();
        Assert.assertEquals(Arrays.asList("renamed", "vanilla_hay", "vanilla_redstone"), ids());

        Assert.assertTrue(state.moveGroupUp(state.views().get(1).key()).accepted());
        settle();
        Assert.assertEquals("上移后顺序保真", Arrays.asList("vanilla_hay", "renamed", "vanilla_redstone"), ids());
        Assert.assertTrue(state.moveGroupTop(state.views().get(2).key()).accepted());
        settle();
        Assert.assertEquals(Arrays.asList("vanilla_redstone", "vanilla_hay", "renamed"), ids());
        Assert.assertTrue(state.moveGroupBottom(state.views().get(0).key()).accepted());
        settle();
        Assert.assertEquals(Arrays.asList("vanilla_hay", "renamed", "vanilla_redstone"), ids());

        Assert.assertTrue(state.toggleGroupMode(state.views().get(0).key(), "chain_base").accepted());
        settle();
        Assert.assertEquals(Arrays.asList("chain_base"), state.views().get(0).modes());
        Assert.assertTrue(state.toggleGroupMode(state.views().get(0).key(), "chain_base").accepted());
        settle();
        Assert.assertTrue(state.views().get(0).modes().isEmpty());

        long hayKey = state.views().get(0).key();
        int before = state.views().get(0).members().size();
        Assert.assertTrue(state.addMember(hayKey, "minecraft:stone@*").accepted());
        settle();
        Assert.assertEquals(before + 1, state.views().get(0).members().size());
        Assert.assertTrue(state.insertMember(hayKey, 0, "minecraft:dirt@*").accepted());
        settle();
        Assert.assertEquals("minecraft:dirt@*", state.views().get(0).members().get(0));
        Assert.assertTrue(state.replaceMember(hayKey, 0, "minecraft:gold_ore@0").accepted());
        settle();
        Assert.assertEquals("minecraft:gold_ore@0", state.views().get(0).members().get(0));
        Assert.assertTrue(state.removeMember(hayKey, 0).accepted());
        settle();
        Assert.assertNotEquals("minecraft:gold_ore@0", state.views().get(0).members().get(0));

        Assert.assertTrue(state.addGroup().accepted());
        settle();
        Assert.assertEquals("group_1", state.views().get(state.views().size() - 1).id());
        Assert.assertTrue(state.duplicateGroup(hayKey).accepted());
        settle();
        Assert.assertEquals("vanilla_hay_copy", state.views().get(1).id());

        assertDraftShapeAndRowsInSync();
    }

    @Test
    public void localGuardsRejectBeforeAnyWrite() {
        Object before = ObjectGroupEditorTestSupport.deepCopy(adapter.draftSignal(PATH).get());
        List<Long> keysBefore = keys();

        long firstKey = state.views().get(0).key();

        Assert.assertEquals(ObjectGroupEditorState.Rejection.ID_DUPLICATED,
                state.renameGroup(firstKey, "vanilla_hay").rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.ID_EMPTY,
                state.renameGroup(firstKey, "").rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.ID_TOO_LONG,
                state.renameGroup(firstKey, repeat("x", ObjectGroup.MAX_ID_LENGTH + 1)).rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.NO_TARGET,
                state.renameGroup(Long.valueOf(-999L), "nope").rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.SELECTOR_UNPARSABLE,
                state.addMember(firstKey, "not-a-selector").rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.SELECTOR_UNPARSABLE,
                state.addMember(firstKey, null).rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.SELECTOR_TOO_LONG,
                state.addMember(firstKey, "minecraft:" + repeat("a", 1100) + "@0").rejection());
        Assert.assertEquals("撤销入口无删除时拒绝", ObjectGroupEditorState.Rejection.NOTHING_TO_UNDO,
                state.undoRemove().rejection());
        settle();

        // 拒绝后草稿与行投影不变
        Assert.assertEquals("被拒绝的命令不得写入草稿", before, adapter.draftSignal(PATH).get());
        Assert.assertEquals("被拒绝的命令不得改变行投影", keysBefore, keys());
    }

    @Test
    public void memberAndGroupLimitsRejectBeforeAnyWrite() {
        // 128/组 上限
        List<Object> fullMembers = new ArrayList<Object>();
        for (int i = 0; i < ObjectGroup.MAX_MEMBERS; i++) {
            fullMembers.add("minecraft:log" + i + "@0");
        }
        installDraft(groups(group("full", Arrays.asList("chain_base"), fullMembers.toArray(new Object[0]))));
        Assert.assertEquals(ObjectGroupEditorState.Rejection.MEMBER_LIMIT,
                state.addMember(state.views().get(0).key(), "minecraft:log@*").rejection());

        // 64 组上限
        List<Object> manyGroups = new ArrayList<Object>();
        for (int i = 0; i < ObjectGroupRuleSet.MAX_GROUPS; i++) {
            manyGroups.add(group("g" + i, Arrays.asList("chain_base"), "minecraft:log" + i + "@0"));
        }
        installDraft(manyGroups);
        Assert.assertEquals(ObjectGroupRuleSet.MAX_GROUPS, state.views().size());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.GROUP_LIMIT, state.addGroup().rejection());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.GROUP_LIMIT,
                state.duplicateGroup(state.views().get(0).key()).rejection());

        // 2048 总量上限（保留一个未满组，确保命中 TOTAL_LIMIT 而不是 MEMBER_LIMIT）
        List<Object> totalGroups = new ArrayList<Object>();
        for (int i = 0; i < 15; i++) {
            totalGroups.add(group("t" + i, Arrays.asList("chain_base"), fill(ObjectGroup.MAX_MEMBERS, i * 128)));
        }
        totalGroups.add(group("t15", Arrays.asList("chain_base"), fill(ObjectGroup.MAX_MEMBERS - 1, 2000)));
        totalGroups.add(group("t16", Arrays.asList("chain_base"), fill(1, 4096)));
        installDraft(totalGroups);
        Assert.assertEquals("构造出 2048 总量", ObjectGroupRuleSet.MAX_TOTAL_MEMBERS,
                state.summary().memberCount());
        Assert.assertEquals(ObjectGroupEditorState.Rejection.TOTAL_LIMIT,
                state.addMember(state.views().get(15).key(), "minecraft:log@*").rejection());
    }

    // ==================================================================
    // §9.1-5 错误映射与 [冲突] 谓词（真源 = ObjectGroupParser.parse().errors()）
    // ==================================================================

    @Test
    public void conflictErrorsMapToBothRowsAndConflictFilter() {
        installDraft(groups(
                group("a", Arrays.asList("chain_base"), "minecraft:log@*"),
                group("b", Arrays.asList("chain_base"), "minecraft:log@0"),
                group("c", new ArrayList<String>(), "minecraft:stone@*")));

        Assert.assertNotNull("重叠冲突必须落到行错误", state.views().get(0).error());
        Assert.assertNotNull(state.views().get(1).error());
        Assert.assertNull("无关行不得被牵连", state.views().get(2).error());
        Assert.assertTrue(state.views().get(0).hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertTrue(state.views().get(1).hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertFalse(state.views().get(2).hasFlag(ObjectGroupEditorState.Flag.CONFLICT));

        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertEquals("谓词 [冲突] 只返回冲突行", Arrays.asList("a", "b"),
                Arrays.asList(state.visibleViews().get(0).id(), state.visibleViews().get(1).id()));
        Assert.assertEquals(2, state.visibleViews().size());

        // 真源唯一：行错误必须逐字等于解析器 errors()，不存在第二份 overlap 判定
        Map<String, String> errors = state.parseResult().errors();
        Assert.assertEquals(errors.get("client.objectGroups[0].modes"), state.views().get(0).error());
        Assert.assertEquals(errors.get("client.objectGroups[1].modes"), state.views().get(1).error());
    }

    @Test
    public void emptyMembersRowIsIncompleteWithRowLocalErrorMessage() {
        installDraft(groups(
                group("a", Arrays.asList("chain_base"), "minecraft:log@*"),
                group("b", Arrays.asList("chain_base"))));

        ObjectGroupEditorState.RowView broken = state.views().get(1);
        Assert.assertTrue("空成员行必须标未完成", broken.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
        Assert.assertFalse(state.views().get(0).hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
        Assert.assertFalse("解析器真源必须报出空成员", state.parseResult().errors().isEmpty());
        Assert.assertTrue("结构性错误仍以字段级键暴露（解析器口径）",
                state.parseResult().errors().containsKey("client.objectGroups"));

        // D3 修复后：结构错误落行（消息文本里的下标被复用），但不冒充「冲突」
        Assert.assertNotNull("空成员行的 error 必须落到该行", broken.error());
        Assert.assertTrue("行错误文案必须点出成员缺失: " + broken.error(),
                broken.error().contains("members"));
        Assert.assertFalse("结构错误不得打 CONFLICT（否则污染 [冲突] 谓词与摘要）",
                broken.hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertTrue("成员为空同时属结构错误 ⇒ 必须有 Flag.ERROR",
                broken.hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertNull("无关行不得被牵连", state.views().get(0).error());
        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertTrue("谓词 [冲突] 不得包含只有结构错误的行", state.visibleViews().isEmpty());
    }

    /**
     * D3 修复后的语义（§9.1-5 + §5.4/§5.5-5 分类口径）：
     * 解析器把「带下标的错误路径」压成字段级键 {@code client.objectGroups}，路径只留在消息文本里；
     * {@code rowErrors} 从消息文本复用下标 ⇒ id 重复也能落到**重复出现的那一行**；
     * 同时只有 {@code client.objectGroups[i].modes} 类错误打 {@link ObjectGroupEditorState.Flag#CONFLICT}，
     * 结构错误只落 {@code RowView.error()}，不污染 {@code [冲突]} 谓词与摘要 conflictCount。
     */
    @Test
    public void duplicateIdMapsToDuplicateRowWithoutConflictPollution() {
        installDraft(groups(
                group("dup", Arrays.asList("chain_base"), "minecraft:log@*"),
                group("dup", Arrays.asList("chain_ore"), "minecraft:stone@*")));

        Assert.assertNull("首次出现的 id 不是错误行", state.views().get(0).error());
        Assert.assertNotNull("重复出现的那一行必须拿到行错误", state.views().get(1).error());
        Assert.assertTrue("行错误文案必须点出重复: " + state.views().get(1).error(),
                state.views().get(1).error().contains("duplicated"));
        Assert.assertFalse("结构错误不得打 CONFLICT",
                state.views().get(1).hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertTrue("结构错误必须打 Flag.ERROR",
                state.views().get(1).hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertEquals("摘要冲突计数不得被结构错误污染", 0, state.summary().conflictCount());

        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertTrue("谓词 [冲突] 只指模式+选择器重叠，不得命中重复 id 行",
                state.visibleViews().isEmpty());

        // 唯一校验真源仍是 parser.errors()（字段级键保留，供整表级提示）
        Assert.assertTrue(state.parseResult().errors().containsKey("client.objectGroups"));
    }

    /**
     * D6 修复后（Flag.ERROR + ListPane 回退 双保险）：结构错误既落 {@link ObjectGroupEditorState.Flag#ERROR}
     * （任何行渲染器可消费），也保留在 {@code RowView.error()}；两类错误严格分开 —— ERROR 不计
     * {@code summary.conflictCount}、不进 {@code [冲突]} 谓词。
     *
     * <p>本用例锚定「非通配 selector + 已知模式 + 非空成员」的行：除 id 重复外没有任何其它状态，
     * 故 ERROR 是唯一状态位（证明它确实来自结构错误，而不是被 INACTIVE/INCOMPLETE 顶替）。</p>
     */
    @Test
    public void structuralErrorSetsErrorFlagWithoutConflictPollution() {
        installDraft(groups(
                group("dup", Arrays.asList("chain_base"), "minecraft:log@0"),
                group("dup", Arrays.asList("chain_ore"), "minecraft:stone@0")));

        ObjectGroupEditorState.RowView broken = state.views().get(1);
        Assert.assertNotNull("错误文案必须落在出错行", broken.error());
        Assert.assertTrue("重复 id 必须打 Flag.ERROR: " + broken.flags(),
                broken.hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertFalse("结构错误不得打 CONFLICT",
                broken.hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertFalse("该行不应有 INCOMPLETE/INACTIVE（证明 ERROR 是独立状态位）",
                broken.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE)
                        || broken.hasFlag(ObjectGroupEditorState.Flag.INACTIVE));
        Assert.assertFalse("干净行不得被误标 ERROR",
                state.views().get(0).hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertEquals("ERROR 不得计入冲突组数", 0, state.summary().conflictCount());
        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertTrue("ERROR 行不得进 [冲突] 谓词", state.visibleViews().isEmpty());
    }

    /** selector 非法（外部草稿）同样落 ERROR，且不进 [冲突]、不计冲突数。 */
    @Test
    public void unparsableSelectorSetsErrorFlagWithoutConflict() {
        installDraft(groups(group("bad", Arrays.asList("chain_base"), "not-a-selector")));

        ObjectGroupEditorState.RowView row = state.views().get(0);
        Assert.assertNotNull(row.error());
        Assert.assertTrue(row.hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertFalse(row.hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertFalse(row.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
        Assert.assertEquals(0, state.summary().conflictCount());

        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertTrue(state.visibleViews().isEmpty());
    }

    /**
     * D7 修复后的数据不变量：同一行可同时携带 {@link ObjectGroupEditorState.Flag#INACTIVE}
     * 与 {@link ObjectGroupEditorState.Flag#ERROR}（例：modes 空 + selector 非法）。
     *
     * <p>可见性由渲染层优先级保证：ListPane 现为 {@code 冲突 > 未完成 > 结构错误 > 未生效}
     * （error 级先于 warning 级），渲染层证据见
     * {@code ObjectGroupEditorRendererHeadlessTest#inactiveRowWithStructuralErrorShowsErrorInList}；
     * ERROR 仍不进 {@code [冲突]}、不计冲突数（本用例同时锚定后者）。</p>
     */
    @Test
    public void inactiveRowWithStructuralErrorCarriesBothFlags() {
        installDraft(groups(group("bad", new ArrayList<String>(), "not-a-selector")));

        ObjectGroupEditorState.RowView row = state.views().get(0);
        Assert.assertTrue(row.hasFlag(ObjectGroupEditorState.Flag.INACTIVE));
        Assert.assertTrue(row.hasFlag(ObjectGroupEditorState.Flag.ERROR));
        Assert.assertFalse(row.hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertNotNull(row.error());
        Assert.assertEquals("两 Flag 共存时不得污染冲突计数", 0, state.summary().conflictCount());

        state.filter().set(ObjectGroupEditorState.Filter.CONFLICT);
        settle();
        Assert.assertTrue("带 ERROR 的行不得进 [冲突] 谓词", state.visibleViews().isEmpty());
    }

    // ==================================================================
    // §9.1-8 新建组
    // ==================================================================

    @Test
    public void newGroupGetsUniqueIdAndIncompleteFlag() {
        Assert.assertTrue(state.addGroup().accepted());
        settle();
        Assert.assertTrue(state.addGroup().accepted());
        settle();

        ObjectGroupEditorState.RowView first = state.views().get(3);
        ObjectGroupEditorState.RowView second = state.views().get(4);
        Assert.assertEquals("group_1", first.id());
        Assert.assertEquals("group_2", second.id());
        Assert.assertNotEquals(first.id(), second.id());
        Assert.assertTrue(first.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
        Assert.assertTrue(second.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
        Assert.assertTrue(first.members().isEmpty());
        Assert.assertTrue("空模式 ⇒ 未生效", first.hasFlag(ObjectGroupEditorState.Flag.INACTIVE));

        // 未完成提示必须能定位到成员缺失：解析器真源里存在对应消息
        Assert.assertEquals(2, state.summary().incompleteCount());
        Assert.assertTrue(state.parseResult().errors().values().iterator().next().contains("members"));
    }

    // ==================================================================
    // §9.1-9 删除撤销
    // ==================================================================

    @Test
    public void undoRemoveRestoresPositionContentAndExpiresAfterStructuralEdit() {
        Assert.assertEquals(3, state.views().size());
        long hayKey = state.views().get(1).key();
        Object hayDraft = ObjectGroupEditorTestSupport.deepCopy(draftGroup(1));

        Assert.assertTrue(state.removeGroup(hayKey).accepted());
        settle();
        Assert.assertEquals(Arrays.asList("vanilla_logs", "vanilla_redstone"), ids());
        Assert.assertTrue("删除后撤销入口可见", Boolean.TRUE.equals(state.canUndoRemove().get()));

        Assert.assertTrue(state.undoRemove().accepted());
        settle();
        Assert.assertEquals("撤销恢复原位次",
                Arrays.asList("vanilla_logs", "vanilla_hay", "vanilla_redstone"), ids());
        Assert.assertEquals("撤销恢复内容", hayDraft, draftGroup(1));
        Assert.assertFalse(Boolean.TRUE.equals(state.canUndoRemove().get()));

        // 再删一次，随后做一次结构性编辑（改名）⇒ 撤销入口失效
        long redstoneKey = state.views().get(2).key();
        Assert.assertTrue(state.removeGroup(redstoneKey).accepted());
        settle();
        Assert.assertTrue(Boolean.TRUE.equals(state.canUndoRemove().get()));
        Assert.assertTrue(state.renameGroup(state.views().get(0).key(), "logs2").accepted());
        settle();
        Assert.assertFalse("结构性编辑后撤销入口必须失效", Boolean.TRUE.equals(state.canUndoRemove().get()));
        Assert.assertEquals(ObjectGroupEditorState.Rejection.NOTHING_TO_UNDO, state.undoRemove().rejection());
    }

    /**
     * D2 修复后：{@code addGroup()} 是结构性编辑，必须让「撤销删除」入口失效
     * （与 {@code undoRemove} javadoc 及 rename/move/duplicate 的既有口径一致）。
     */
    @Test
    public void addGroupInvalidatesUndoStash() {
        Assert.assertEquals(3, state.views().size());
        Assert.assertTrue(state.removeGroup(state.views().get(1).key()).accepted());
        settle();
        Assert.assertTrue(Boolean.TRUE.equals(state.canUndoRemove().get()));

        Assert.assertTrue(state.addGroup().accepted());
        settle();
        Assert.assertFalse("新建组后撤销入口必须失效", Boolean.TRUE.equals(state.canUndoRemove().get()));
        Assert.assertEquals(ObjectGroupEditorState.Rejection.NOTHING_TO_UNDO, state.undoRemove().rejection());
    }

    // ==================================================================
    // §5.5-3 失效 modes 保留为可取消项
    // ==================================================================

    @Test
    public void unknownModesArePreservedLosslessly() {
        installDraft(groups(group("a", Arrays.asList("chain_base", "legacy_mode_x", "legacy_mode_y"),
                "minecraft:log@*")));
        ObjectGroupEditorState.RowView view = state.views().get(0);
        Assert.assertEquals(Arrays.asList("chain_base"), view.modes());
        Assert.assertEquals(Arrays.asList("legacy_mode_x", "legacy_mode_y"), view.unknownModes());
        Assert.assertTrue(view.hasFlag(ObjectGroupEditorState.Flag.WILDCARD));

        // 已知模式切换不得丢弃失效值
        Assert.assertTrue(state.toggleGroupMode(view.key(), "chain_ore").accepted());
        settle();
        Assert.assertEquals("新增已知模式按追加保序", Arrays.asList("chain_base", "chain_ore"),
                state.views().get(0).modes());
        Assert.assertEquals(Arrays.asList("legacy_mode_x", "legacy_mode_y"),
                state.views().get(0).unknownModes());
        Assert.assertEquals(Arrays.asList("chain_base", "chain_ore", "legacy_mode_x", "legacy_mode_y"),
                draftModes(0));
    }

    /**
     * 必须由 owner 修的缺陷（M6，{@code ObjectGroupEditorState.removeUnknownMode}）：取消一个失效模式值
     * 取消一个失效模式值必须**只**移除该值：其余失效值逐一保留一次（无损口径）。
     *
     * <p>回归背景（D1）：旧实现先按 raw 过滤重建 modes（已含剩余失效值），再二次追加一份剩余失效值
     * ⇒ modes=[chain_base, legacy_mode_y, legacy_mode_y]。修复后应恰为 [chain_base, legacy_mode_y]。</p>
     */
    @Test
    public void removeUnknownModeKeepsRemainingUnknownsExactlyOnce() {
        installDraft(groups(group("a", Arrays.asList("chain_base", "legacy_mode_x", "legacy_mode_y"),
                "minecraft:log@*")));
        long key = state.views().get(0).key();
        Assert.assertTrue(state.removeUnknownMode(key, "legacy_mode_x").accepted());
        settle();
        Assert.assertEquals("剩余失效值必须只保留一份",
                Arrays.asList("chain_base", "legacy_mode_y"), draftModes(0));
        Assert.assertEquals(Arrays.asList("legacy_mode_y"), state.views().get(0).unknownModes());
        Assert.assertEquals("已知模式不受影响", Arrays.asList("chain_base"), state.views().get(0).modes());

        // 再取消最后一个失效值 ⇒ 只剩已知模式
        Assert.assertTrue(state.removeUnknownMode(key, "legacy_mode_y").accepted());
        settle();
        Assert.assertEquals(Arrays.asList("chain_base"), draftModes(0));
        Assert.assertTrue(state.views().get(0).unknownModes().isEmpty());
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private List<Long> keys() {
        List<Long> keys = new ArrayList<Long>();
        for (ObjectGroupEditorState.RowView view : state.views()) {
            keys.add(Long.valueOf(view.key()));
        }
        return keys;
    }

    private List<String> ids() {
        settle();
        List<String> ids = new ArrayList<String>();
        for (ObjectGroupEditorState.RowView view : state.views()) {
            ids.add(view.id());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<Object> draftGroups() {
        return (List<Object>) adapter.draftSignal(PATH).get();
    }

    private Object draftGroup(int index) {
        return ObjectGroupEditorTestSupport.deepCopy(draftGroups().get(index));
    }

    @SuppressWarnings("unchecked")
    private List<Object> draftModes(int index) {
        return (List<Object>) ((Map<Object, Object>) draftGroups().get(index)).get("modes");
    }

    @SuppressWarnings("unchecked")
    private void assertDraftShapeAndRowsInSync() {
        Object raw = adapter.draftSignal(PATH).get();
        Assert.assertTrue(raw instanceof List);
        for (Object item : (List<Object>) raw) {
            Assert.assertTrue(item instanceof Map);
            Map<Object, Object> map = (Map<Object, Object>) item;
            Assert.assertEquals("草稿每项必须且只有 id/modes/members 三键（保序）",
                    Arrays.asList("id", "modes", "members"), new ArrayList<Object>(map.keySet()));
            Assert.assertTrue(map.get("modes") instanceof List);
            Assert.assertTrue(map.get("members") instanceof List);
        }
        Assert.assertTrue("行投影与草稿必须同源一致",
                StructuredListModel.valuesEqual(state.rows().get(), raw));
    }

    private static Object[] fill(int count, int seed) {
        Object[] out = new Object[count];
        for (int i = 0; i < count; i++) {
            out[i] = "minecraft:log" + (seed + i) + "@0";
        }
        return out;
    }

    private static String repeat(String unit, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    private static List<Object> groups(Object... groupValues) {
        return new ArrayList<Object>(Arrays.asList(groupValues));
    }

    private static Map<String, Object> group(String id, List<String> modes, Object... members) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", new ArrayList<Object>(modes));
        group.put("members", new ArrayList<Object>(Arrays.asList(members)));
        return group;
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
