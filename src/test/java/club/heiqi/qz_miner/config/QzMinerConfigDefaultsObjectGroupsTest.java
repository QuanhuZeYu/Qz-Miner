package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorState;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;

/**
 * 出厂默认对象组（{@code client.objectGroups}）的契约测试。
 *
 * <p><b>契约</b>：默认值唯一真源是 {@link QzMinerConfigDefaults#objectGroups()}，形状固定为
 * 1 组「红石矿石」（modes 四项全开、4 条 {@code registry@*} 成员）。本类逐条验证「解析器能解、
 * 语义校验零错误、编辑器状态零冲突且已生效、真实写盘以 UTF-8 保存中文 id 且读回全等」——
 * 均为实跑断言，不靠推断。</p>
 *
 * <p><b>注册表边界</b>：{@code etfuturum} 只做 selector 语法解析（{@link ObjectGroupParser#parseSelector}），
 * 不查实机 mod 注册表；「未安装 etfuturum 时是否命中」不属于本层契约。</p>
 */
public class QzMinerConfigDefaultsObjectGroupsTest {

    private static final String PATH = "client.objectGroups";

    /** 默认组模式（顺序即契约顺序）。 */
    private static final List<String> EXPECTED_MODES = Collections.unmodifiableList(Arrays.asList(
            ObjectGroupMode.CHAIN_BASE,
            ObjectGroupMode.CHAIN_ORE,
            ObjectGroupMode.AREA_SAME_BLOCK,
            ObjectGroupMode.AREA_ORE));

    /** 默认组成员（顺序即契约顺序）。 */
    private static final List<String> EXPECTED_MEMBERS = Collections.unmodifiableList(Arrays.asList(
            "minecraft:redstone_ore@*",
            "minecraft:lit_redstone_ore@*",
            "etfuturum:deepslate_redstone_ore@*",
            "etfuturum:deepslate_lit_redstone_ore@*"));

    /** chain_base|chain_ore|area_same_block|area_ore = 1|2|8|16（Python 验算 hex 0x1b）。 */
    private static final long EXPECTED_MODE_MASK = 27L;

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-default-objectgroups-").toFile();
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    // ==================================================================
    // 1. 形状：恰好 1 组、三键保序、内容逐项全等、不可变
    // ==================================================================

    @Test
    public void shippedDefaultIsExactlyOneOrderedImmutableGroup() {
        List<Map<String, Object>> groups = QzMinerConfigDefaults.objectGroups();
        Assert.assertEquals("出厂默认恰好 1 组", 1, groups.size());

        Map<String, Object> group = groups.get(0);
        Assert.assertEquals("组必须且只有 id/modes/members 三键（保序）",
                Arrays.asList("id", "modes", "members"), new ArrayList<Object>(group.keySet()));
        Assert.assertEquals("红石矿石", group.get("id"));
        Assert.assertEquals(EXPECTED_MODES, group.get("modes"));
        Assert.assertEquals(EXPECTED_MEMBERS, group.get("members"));
        Assert.assertTrue("id 必须满足 ObjectGroup.MAX_ID_LENGTH（实测 "
                        + ((String) group.get("id")).length() + "）",
                ((String) group.get("id")).length() <= ObjectGroup.MAX_ID_LENGTH);
        Assert.assertEquals("modes 不得混入其它已知模式", 4, ((List<?>) group.get("modes")).size());
        Assert.assertEquals("members 不得多/少", 4, ((List<?>) group.get("members")).size());

        assertUnmodifiableList(groups);
        assertUnmodifiableList((List<?>) group.get("modes"));
        assertUnmodifiableList((List<?>) group.get("members"));
        assertUnmodifiableMap(group);
    }

    // ==================================================================
    // 2. 解析器：4 条成员全部可解析 + 整值解析为有效规则集
    // ==================================================================

    @Test
    public void shippedDefaultParsesWithEveryMemberSelectorAccepted() {
        List<Map<String, Object>> groups = QzMinerConfigDefaults.objectGroups();
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) groups.get(0).get("members");
        Assert.assertEquals(EXPECTED_MEMBERS, members);

        for (String value : members) {
            ObjectGroupSelector selector = ObjectGroupParser.parseSelector(value);
            Assert.assertEquals("registry 必须逐字保留: " + value,
                    value.substring(0, value.lastIndexOf('@')), selector.registry());
            Assert.assertEquals("默认成员全部是 registry@* 通配: " + value,
                    ObjectGroupSelector.Specificity.WILDCARD, selector.specificity());
            Assert.assertEquals("规范化 round-trip 必须与原文一致: " + value,
                    value, selector.canonical());
        }

        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(groups);
        Assert.assertTrue("出厂默认必须解析为有效规则集（单组无重叠）: " + parsed.error(), parsed.isValid());
        ObjectGroupRuleSet rules = parsed.rules();
        Assert.assertEquals(1, rules.groups().size());

        ObjectGroup group = rules.groups().get(0);
        Assert.assertEquals("红石矿石", group.id());
        Assert.assertEquals(EXPECTED_MODES, group.modes());
        Assert.assertEquals("模式位掩码必须恰为 chain_base|chain_ore|area_same_block|area_ore",
                EXPECTED_MODE_MASK, group.modeMask());
        Assert.assertEquals(4, group.members().size());
        for (int i = 0; i < EXPECTED_MEMBERS.size(); i++) {
            Assert.assertEquals(EXPECTED_MEMBERS.get(i), group.members().get(i).canonical());
        }
    }

    // ==================================================================
    // 3. 语义校验：ConfigSemanticValidator 零错误 + 提交快照已生效
    // ==================================================================

    @Test
    public void shippedDefaultPassesSemanticValidatorAndCommitsAsActiveGroup() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.captureAndValidate(manager);
        Assert.assertTrue("出厂默认必须通过 ConfigSemanticValidator（零错误）: "
                + outcome.result.summary(), outcome.isValid());
        Assert.assertTrue("校验结果不得含任何字段错误", outcome.result.errors().isEmpty());

        ObjectGroupRuleSet rules = outcome.snapshot.objectGroups;
        Assert.assertNotNull("快照必须含已解析对象组规则集", rules);
        Assert.assertEquals(1, rules.groups().size());
        ObjectGroup group = rules.groups().get(0);
        Assert.assertEquals("红石矿石", group.id());
        Assert.assertEquals(EXPECTED_MODES, group.modes());
        Assert.assertEquals(EXPECTED_MODE_MASK, group.modeMask());
        Assert.assertEquals(4, group.members().size());

        ObjectGroupRuleSet committed = ConfigBootstrap.currentValidatedSnapshot().objectGroups;
        Assert.assertEquals("启动提交快照必须就是该默认组（已生效，不是未配置态）",
                "红石矿石", committed.groups().get(0).id());
        Assert.assertEquals(EXPECTED_MODE_MASK, committed.groups().get(0).modeMask());
    }

    // ==================================================================
    // 4. 编辑器状态：零冲突 / 零错误 / 已生效 / 可编辑
    // ==================================================================

    @Test
    public void shippedDefaultIsConflictFreeErrorFreeActiveAndEditableInEditorState() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        DraftBuffer draft = manager.openDraft();
        DraftSignalAdapter adapter = new DraftSignalAdapter(null, draft);
        try {
            FieldSpec spec = QzMinerConfigSchema.create().field(ObjectGroupEditorState.PATH);
            ObjectGroupEditorState state = new ObjectGroupEditorState(spec, adapter);

            ObjectGroupEditorState.Summary summary = state.summary();
            Assert.assertEquals(1, summary.groupCount());
            Assert.assertEquals(4, summary.memberCount());
            Assert.assertEquals("单组默认不可能存在「模式+选择器重叠」", 0, summary.conflictCount());
            Assert.assertEquals("默认组 modes 非空 ⇒ 不得是未生效态", 0, summary.inactiveCount());
            Assert.assertEquals("默认组成员非空 ⇒ 不得是未完成态", 0, summary.incompleteCount());
            Assert.assertTrue("解析器真源必须零错误: " + state.parseResult().error(),
                    state.parseResult().isValid());
            Assert.assertTrue(state.parseResult().errors().isEmpty());

            ObjectGroupEditorState.RowView view = state.views().get(0);
            Assert.assertEquals("红石矿石", view.id());
            Assert.assertEquals(EXPECTED_MODES, view.modes());
            Assert.assertEquals(EXPECTED_MEMBERS, view.members());
            Assert.assertTrue("默认不得含失效模式", view.unknownModes().isEmpty());
            Assert.assertFalse(view.hasFlag(ObjectGroupEditorState.Flag.INACTIVE));
            Assert.assertFalse(view.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE));
            Assert.assertFalse(view.hasFlag(ObjectGroupEditorState.Flag.ERROR));
            Assert.assertFalse(view.hasFlag(ObjectGroupEditorState.Flag.CONFLICT));
            Assert.assertTrue("4 条成员都是通配选择器", view.hasFlag(ObjectGroupEditorState.Flag.WILDCARD));
            Assert.assertFalse("默认组不得命中 [冲突] 谓词", view.matches(ObjectGroupEditorState.Filter.CONFLICT));
            Assert.assertFalse("默认组不得命中 [未生效] 谓词", view.matches(ObjectGroupEditorState.Filter.INACTIVE));

            // 已生效的正面证据：真实编辑命令被接受并落到同一草稿（不是只读快照）
            Assert.assertTrue("默认组必须可编辑",
                    state.toggleGroupMode(view.key(), ObjectGroupMode.CHAIN_ORE).accepted());
            ReactiveScheduler.get().flush();
            Assert.assertEquals(Arrays.asList(
                    ObjectGroupMode.CHAIN_BASE, ObjectGroupMode.AREA_SAME_BLOCK, ObjectGroupMode.AREA_ORE),
                    state.views().get(0).modes());
            Assert.assertEquals("编辑必须写回同一草稿", 3,
                    ((List<?>) ((Map<?, ?>) ((List<?>) draft.getDraft(PATH)).get(0)).get("modes")).size());
        } finally {
            adapter.dispose();
        }
    }

    // ==================================================================
    // 5. 中文 id 的 UTF-8 写盘 / 读回 round-trip
    // ==================================================================

    @Test
    public void shippedDefaultWritesAndReloadsChineseIdAsUtf8() throws Exception {
        ConfigBootstrap.bootstrap(tempDir, null);
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Assert.assertTrue("默认配置必须落盘: " + yaml.getAbsolutePath(), yaml.isFile());

        byte[] bytes = Files.readAllBytes(yaml.toPath());
        String text = strictUtf8(bytes);
        Assert.assertTrue("落盘 YAML 必须原样含中文 id「红石矿石」", text.contains("红石矿石"));
        Assert.assertTrue("落盘 YAML 必须含 etfuturum 成员",
                text.contains("etfuturum:deepslate_lit_redstone_ore@*"));
        Assert.assertTrue("必须按 UTF-8 字节落盘（12 字节序列原样存在）",
                indexOf(bytes, "红石矿石".getBytes(StandardCharsets.UTF_8)) >= 0);

        // 证据输出（进 test-results system-out，供报告引用真实落盘内容）
        int at = text.indexOf("红石矿石");
        System.out.println("[default-og][utf8] yaml=" + yaml.getAbsolutePath()
                + " bytes=" + bytes.length
                + " utf8SeqOffset=" + indexOf(bytes, "红石矿石".getBytes(StandardCharsets.UTF_8))
                + " snippet=\n"
                + text.substring(Math.max(0, at - 160), Math.min(text.length(), at + 220)));

        ConfigBootstrap.resetForTests();
        ConfigManager reloaded = ConfigBootstrap.bootstrap(tempDir, null);
        Object raw = reloaded.authority().get(PATH);
        Assert.assertEquals("读回值必须与出厂默认全等（顺序/modes/members）",
                QzMinerConfigDefaults.objectGroups(), raw);

        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(raw);
        Assert.assertTrue("读回值必须仍可解析: " + parsed.error(), parsed.isValid());
        ObjectGroup group = parsed.rules().groups().get(0);
        Assert.assertEquals("中文 id 必须原样读回（无乱码）", "红石矿石", group.id());
        Assert.assertEquals(EXPECTED_MODES, group.modes());
        for (int i = 0; i < EXPECTED_MEMBERS.size(); i++) {
            Assert.assertEquals(EXPECTED_MEMBERS.get(i), group.members().get(i).canonical());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 严格 UTF-8 解码：遇到非法字节直接失败（用于证明文件确实是 UTF-8，而非平台默认编码）。 */
    private static String strictUtf8(byte[] bytes) throws Exception {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static void assertUnmodifiableList(List<?> list) {
        try {
            ((List<Object>) list).add("mutated");
            Assert.fail("默认值列表必须不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertUnmodifiableMap(Map<?, ?> map) {
        try {
            ((Map<Object, Object>) map).put("mutated", "mutated");
            Assert.fail("默认值组必须不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
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
