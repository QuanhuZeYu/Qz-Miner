package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorState;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;

/**
 * 出厂默认对象组（{@code client.objectGroups}）的契约测试。
 *
 * <p><b>为什么不再冻结「几组/几条成员」</b>：默认对象组是随 issue 演进的覆盖列表（#183/#251 红石矿石、
 * #249 极光方块都靠增补条目修复），把组数与成员数写成字面量只会让每次正常增补都改测试，却挡不住真正
 * 会复发的形态错误（modes 空、成员不可解析、组间同 registry 重叠、条目永不命中）。本类因此只断言
 * <b>行为</b>：结构完整可解析、每个默认组四模式全开（mask=27）、每个声明模式位下每条成员都能真的 resolve、
 * 极光条目保持单 registry 白名单、两个历史回归场景（含全部成员与被桥接对象）仍成立、
 * 默认值能通过语义校验并落盘 round-trip、编辑器零冲突且可编辑。</p>
 *
 * <p><b>注册表边界</b>：{@code etfuturum} / {@code TwilightForest} 只做 selector 语法与默认覆盖断言，
 * 不查实机 mod 注册表；「未安装该 mod 时是否命中」不属于本层契约。极光方块的注册名字符串未实机验证，
 * 本类只保证「默认里声明的那个 registry 能跨 metadata 相位桥接」这一行为不变。</p>
 */
public class QzMinerConfigDefaultsObjectGroupsTest {

    private static final String PATH = "client.objectGroups";

    /** #183/#251 的回归输入：红石矿石本体与点燃态 registry（同名跨 registry 桥接）。 */
    private static final String REDSTONE_ORE = "minecraft:redstone_ore";
    private static final String LIT_REDSTONE_ORE = "minecraft:lit_redstone_ore";

    /** #183/#251 的可选 mod 变体成员（mod 未安装时只是不命中，不影响配置合法性）。 */
    private static final String ETFR_DEEPSLATE_REDSTONE_ORE = "etfuturum:deepslate_redstone_ore";
    private static final String ETFR_DEEPSLATE_LIT_REDSTONE_ORE = "etfuturum:deepslate_lit_redstone_ore";

    /** #249 的回归输入：暮色森林极光方块 registry（metadata 为位置相位，必须整体忽略 meta）。 */
    private static final String AURORA_BLOCK = "TwilightForest:tile.TFAuroraBrick";

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
    // 1. 结构：只断言「可用性」，不冻结组数/成员数
    // ==================================================================

    @Test
    public void shippedDefaultGroupsAreCompleteParsableAndImmutable() {
        List<Map<String, Object>> groups = QzMinerConfigDefaults.objectGroups();
        Assert.assertFalse("出厂默认不得为空：空默认等于对象组机制整体失效", groups.isEmpty());
        Assert.assertTrue("组数不得超过 ObjectGroupRuleSet.MAX_GROUPS",
                groups.size() <= ObjectGroupRuleSet.MAX_GROUPS);

        Set<String> ids = new HashSet<String>();
        int totalMembers = 0;
        for (int index = 0; index < groups.size(); index++) {
            Map<String, Object> group = groups.get(index);
            Assert.assertEquals("第 " + index + " 组必须且只有 id/modes/members 三键（保序）",
                    Arrays.asList("id", "modes", "members"), new ArrayList<Object>(group.keySet()));

            String id = String.valueOf(group.get("id"));
            Assert.assertFalse("组 id 不得为空: 第 " + index + " 组", id.isEmpty());
            Assert.assertTrue("组 id 必须满足 ObjectGroup.MAX_ID_LENGTH（实测 " + id.length() + "）",
                    id.length() <= ObjectGroup.MAX_ID_LENGTH);
            Assert.assertTrue("组 id 必须唯一: " + id, ids.add(id));

            List<?> modes = (List<?>) group.get("modes");
            Assert.assertFalse("modes 不得为空：空 modes 的组永不生效（#183/#251 的回归形态）: " + id,
                    modes.isEmpty());
            List<?> members = (List<?>) group.get("members");
            Assert.assertFalse("members 不得为空：空成员的组永不命中: " + id, members.isEmpty());
            Assert.assertTrue("单组成员数不得超过 ObjectGroup.MAX_MEMBERS: " + id,
                    members.size() <= ObjectGroup.MAX_MEMBERS);
            for (Object member : members) {
                Assert.assertTrue("成员必须是字符串 selector: " + member, member instanceof String);
                Assert.assertFalse("成员不得为空串: " + id, String.valueOf(member).isEmpty());
            }
            totalMembers += members.size();

            assertUnmodifiableList(groups);
            assertUnmodifiableList(modes);
            assertUnmodifiableList(members);
            assertUnmodifiableMap(group);
        }
        Assert.assertTrue("成员总数不得超过 ObjectGroupRuleSet.MAX_TOTAL_MEMBERS",
                totalMembers <= ObjectGroupRuleSet.MAX_TOTAL_MEMBERS);
    }

    // ==================================================================
    // 2. 每个声明模式位下每条成员都必须真的命中（否则是死配置）
    // ==================================================================

    @Test
    public void everyShippedMemberResolvesUnderEveryDeclaredMode() {
        ObjectGroupRuleSet rules = parseShippedDefault();
        Assert.assertEquals("解析结果必须与出厂默认组数一致",
                QzMinerConfigDefaults.objectGroups().size(), rules.groups().size());

        for (ObjectGroup group : rules.groups()) {
            // D-2 行为契约：默认组必须四模式全开 = CHAIN_BASE|CHAIN_ORE|AREA_SAME_BLOCK|AREA_ORE
            // = 1|2|8|16 = 27 (0x1B)，数值由 ObjectGroupMode 的稳定位推导（Python 验算）；0 表示永不生效。
            Assert.assertEquals("每个默认组必须四模式全开（mask=27=0x1B）: " + group.id(),
                    27L, group.modeMask());
            for (ObjectGroupSelector selector : group.members()) {
                Assert.assertEquals("selector canonical round-trip 必须与配置原文一致: "
                        + selector.canonical(), selector.canonical(),
                        ObjectGroupParser.parseSelector(selector.canonical()).canonical());
                int probeMeta = probeMetadata(selector);
                for (String mode : group.modes()) {
                    long mask = ObjectGroupMode.toMask(Collections.singletonList(mode));
                    ModeExtensionSnapshot frozen = rules.resolve(mask, selector.registry(), probeMeta);
                    Assert.assertFalse("成员在声明模式位下必须真的命中（否则是死配置）: group=" + group.id()
                            + " mode=" + mode + " selector=" + selector.canonical(), frozen.isEmpty());
                    Assert.assertTrue("冻结快照必须允许该成员自身: " + selector.canonical(),
                            frozen.matches(selector.registry(), probeMeta));
                }
            }
        }
    }

    // ==================================================================
    // 3. 回归 #183/#251：红石矿石跨 registry 桥接（默认组的存在意义）
    // ==================================================================

    @Test
    public void shippedDefaultBridgesRedstoneOreVariantsOnChainBase() {
        ObjectGroupRuleSet rules = parseShippedDefault();
        long chainBase = chainBaseMask();

        ModeExtensionSnapshot frozen = rules.resolve(chainBase, REDSTONE_ORE, 0);
        Assert.assertFalse("默认必须覆盖红石矿石本体（#183/#251 回归）", frozen.isEmpty());
        Assert.assertTrue("必须能桥接到点燃态 registry", frozen.matches(LIT_REDSTONE_ORE, 0));
        Assert.assertTrue("必须能桥接到可选 mod 的深板岩变体（未安装时只是不命中，不影响配置合法性）",
                frozen.matches(ETFR_DEEPSLATE_LIT_REDSTONE_ORE, 3));

        // D-1：第 4 条成员（EFR 深板岩红石矿本体）也必须真的命中——它作为种子时同组其它成员都要可桥接。
        ModeExtensionSnapshot deepslateSeed = rules.resolve(chainBase, ETFR_DEEPSLATE_REDSTONE_ORE, 0);
        Assert.assertFalse("默认必须覆盖深板岩红石矿本体（第 4 条成员）", deepslateSeed.isEmpty());
        Assert.assertTrue("深板岩红石矿本体必须桥接到点燃态深板岩变体",
                deepslateSeed.matches(ETFR_DEEPSLATE_LIT_REDSTONE_ORE, 1));
        Assert.assertTrue("深板岩红石矿本体必须桥接回原版红石矿本体",
                deepslateSeed.matches(REDSTONE_ORE, 0));
        Assert.assertFalse("不得连带覆盖无关 registry", frozen.matches("minecraft:stone", 0));
    }

    // ==================================================================
    // 4. 回归 #249：极光方块 metadata 位置相位必须整体忽略（且不扩散到其它方块）
    // ==================================================================

    @Test
    public void shippedDefaultBridgesAuroraPositionPhaseMetadataOnChainBase() {
        ObjectGroupRuleSet rules = parseShippedDefault();
        long chainBase = chainBaseMask();

        ModeExtensionSnapshot frozen = rules.resolve(chainBase, AURORA_BLOCK, 0);
        Assert.assertFalse("默认必须覆盖暮色森林极光方块（#249 回归）", frozen.isEmpty());
        for (int meta : new int[] {0, 1, 5, 6, 15}) {
            Assert.assertTrue("极光方块条目必须忽略 metadata 位置相位（#249）: meta=" + meta,
                    frozen.matches(AURORA_BLOCK, meta));
        }
        // D-4：旧断言写的 TwilightForest:tile.TFAuroraPillar 并不存在（TF 上游 TFBlocks.java:114 与 GTNH 分叉
        // 均以 setBlockName("AuroraPillar") 注册 ⇒ 实际为 TwilightForest:tile.AuroraPillar），命不中任何域名
        // ⇒ 恒真、假防线。这里改用不依赖该命名的等价判据：极光条目必须是「单 registry 白名单」。
        Set<String> auroraRegistries = new HashSet<String>();
        for (ObjectGroup group : rules.groups()) {
            if (group.id().equals(frozen.groupId())) {
                for (ObjectGroupSelector selector : group.members()) {
                    auroraRegistries.add(selector.registry());
                }
            }
        }
        Assert.assertEquals("极光条目必须是单 registry 白名单（把 AuroraPillar/Slab 并入该组即失败）",
                1, auroraRegistries.size());
        Assert.assertFalse("不得连带放宽相邻极光柱 registry（TwilightForest:tile.AuroraPillar，GTNH 分叉 TFBlocks:249 核对）",
                frozen.matches("TwilightForest:tile.AuroraPillar", 0));
        Assert.assertFalse("不得连带放宽 vanilla 石头的变体 meta", frozen.matches("minecraft:stone", 1));
    }

    // ==================================================================
    // 5. 语义校验：零错误 + 提交快照逐组等于出厂默认（不是未配置态）
    // ==================================================================

    @Test
    public void shippedDefaultPassesSemanticValidatorAndCommitsActiveGroups() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.captureAndValidate(manager);
        Assert.assertTrue("出厂默认必须通过 ConfigSemanticValidator（零错误）: "
                + outcome.result.summary(), outcome.isValid());
        Assert.assertTrue("校验结果不得含任何字段错误", outcome.result.errors().isEmpty());

        ObjectGroupRuleSet rules = outcome.snapshot.objectGroups;
        Assert.assertNotNull("快照必须含已解析对象组规则集", rules);
        Assert.assertFalse("快照不得是未配置态（空规则集）", rules.groups().isEmpty());
        for (ObjectGroup group : rules.groups()) {
            Assert.assertNotEquals("提交快照里不得有永不生效的组: " + group.id(), 0L, group.modeMask());
            Assert.assertFalse("提交快照里不得有永不命中的组: " + group.id(), group.members().isEmpty());
        }
        Assert.assertEquals("启动提交快照必须逐组等于出厂默认（不是未配置态）",
                signature(rules), signature(ConfigBootstrap.currentValidatedSnapshot().objectGroups));
    }

    // ==================================================================
    // 6. 编辑器状态：零冲突 / 零错误 / 已生效 / 可编辑（不冻结行数）
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
            Assert.assertTrue("默认必须挂出至少一行组视图", summary.groupCount() >= 1);
            Assert.assertEquals("默认不得存在「模式+选择器重叠」的组", 0, summary.conflictCount());
            Assert.assertEquals("默认组 modes 非空 ⇒ 不得有未生效组", 0, summary.inactiveCount());
            Assert.assertEquals("默认组成员非空 ⇒ 不得有未完成组", 0, summary.incompleteCount());
            Assert.assertTrue("解析器真源必须零错误: " + state.parseResult().error(),
                    state.parseResult().isValid());
            Assert.assertTrue(state.parseResult().errors().isEmpty());

            for (ObjectGroupEditorState.RowView view : state.views()) {
                Assert.assertTrue("默认组不得含失效模式: " + view.id(), view.unknownModes().isEmpty());
                Assert.assertFalse("默认组不得命中 [冲突] 谓词: " + view.id(),
                        view.matches(ObjectGroupEditorState.Filter.CONFLICT));
                Assert.assertFalse("默认组不得命中 [未生效] 谓词: " + view.id(),
                        view.matches(ObjectGroupEditorState.Filter.INACTIVE));
                for (ObjectGroupEditorState.Flag flag : new ObjectGroupEditorState.Flag[] {
                        ObjectGroupEditorState.Flag.INACTIVE, ObjectGroupEditorState.Flag.INCOMPLETE,
                        ObjectGroupEditorState.Flag.ERROR, ObjectGroupEditorState.Flag.CONFLICT}) {
                    Assert.assertFalse("默认组不得带状态位 " + flag + ": " + view.id(), view.hasFlag(flag));
                }
                Assert.assertFalse("默认组模式不得为空: " + view.id(), view.modes().isEmpty());
                Assert.assertFalse("默认组成员不得为空: " + view.id(), view.members().isEmpty());
            }
            Assert.assertEquals("行视图必须与默认组一一对应",
                    QzMinerConfigDefaults.objectGroups().size(), state.views().size());

            // 已生效的正面证据：真实编辑命令被接受并落到同一草稿（不是只读快照）
            ObjectGroupEditorState.RowView first = state.views().get(0);
            List<String> before = new ArrayList<String>(first.modes());
            List<String> expectedAfter = new ArrayList<String>(before);
            if (!expectedAfter.remove(ObjectGroupMode.CHAIN_LOGGING)) {
                expectedAfter.add(ObjectGroupMode.CHAIN_LOGGING);
            }
            Assert.assertTrue("默认组必须可编辑",
                    state.toggleGroupMode(first.key(), ObjectGroupMode.CHAIN_LOGGING).accepted());
            ReactiveScheduler.get().flush();
            Assert.assertEquals("切换模式必须只增删该模式并写回同一草稿", expectedAfter,
                    draftModeList(draft, 0));
            Assert.assertEquals("切换模式不得增删组",
                    QzMinerConfigDefaults.objectGroups().size(), state.views().size());
        } finally {
            adapter.dispose();
        }
    }

    // ==================================================================
    // 7. 中文 id / 全量成员 的 UTF-8 写盘 + 读回 round-trip
    // ==================================================================

    @Test
    public void shippedDefaultWritesAndReloadsEveryGroupAsUtf8() throws Exception {
        ConfigBootstrap.bootstrap(tempDir, null);
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Assert.assertTrue("默认配置必须落盘: " + yaml.getAbsolutePath(), yaml.isFile());

        byte[] bytes = Files.readAllBytes(yaml.toPath());
        String text = strictUtf8(bytes);
        for (Map<String, Object> group : QzMinerConfigDefaults.objectGroups()) {
            String id = String.valueOf(group.get("id"));
            Assert.assertTrue("落盘 YAML 必须原样含组 id: " + id, text.contains(id));
            Assert.assertTrue("组 id 必须按 UTF-8 字节序列原样落盘: " + id,
                    indexOf(bytes, id.getBytes(StandardCharsets.UTF_8)) >= 0);
            for (Object member : (List<?>) group.get("members")) {
                String selector = String.valueOf(member);
                Assert.assertTrue("落盘 YAML 必须原样含成员 selector: " + selector, text.contains(selector));
            }
        }

        // 证据输出（进 test-results system-out，供报告引用真实落盘内容）
        String firstId = String.valueOf(QzMinerConfigDefaults.objectGroups().get(0).get("id"));
        int at = text.indexOf(firstId);
        System.out.println("[default-og][utf8] yaml=" + yaml.getAbsolutePath()
                + " bytes=" + bytes.length
                + " utf8SeqOffset=" + indexOf(bytes, firstId.getBytes(StandardCharsets.UTF_8))
                + " snippet=\n"
                + text.substring(Math.max(0, at - 160), Math.min(text.length(), at + 320)));

        ConfigBootstrap.resetForTests();
        ConfigManager reloaded = ConfigBootstrap.bootstrap(tempDir, null);
        Object raw = reloaded.authority().get(PATH);
        Assert.assertEquals("读回值必须与出厂默认全等（顺序/modes/members）",
                QzMinerConfigDefaults.objectGroups(), raw);

        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(raw);
        Assert.assertTrue("读回值必须仍可解析: " + parsed.error(), parsed.isValid());
        Assert.assertEquals("读回后每组 id 与成员必须原样（中文无乱码）",
                signature(parseShippedDefault()), signature(parsed.rules()));
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** @return 出厂默认解析后的不可变规则集；默认配置非法时直接失败 */
    private static ObjectGroupRuleSet parseShippedDefault() {
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(QzMinerConfigDefaults.objectGroups());
        Assert.assertTrue("出厂默认必须解析为有效规则集（含跨组重叠校验）: " + parsed.error(),
                parsed.isValid());
        return parsed.rules();
    }

    /** @return chain_base 的模式位掩码（回归断言的模式输入，取自稳定常量） */
    private static long chainBaseMask() {
        return ObjectGroupMode.toMask(Collections.singletonList(ObjectGroupMode.CHAIN_BASE));
    }

    /** @return 逐组签名（id + modes 顺序 + members canonical 顺序），用于跨表示形式比较同一配置 */
    private static List<String> signature(ObjectGroupRuleSet rules) {
        List<String> signature = new ArrayList<String>();
        Assert.assertNotNull("规则集不得为 null", rules);
        for (ObjectGroup group : rules.groups()) {
            StringBuilder row = new StringBuilder(group.id()).append('|').append(group.modes());
            for (ObjectGroupSelector selector : group.members()) {
                row.append('|').append(selector.canonical());
            }
            signature.add(row.toString());
        }
        return signature;
    }

    /** @return selector 自描述的一个可命中 metadata（通配取非零值，证明不依赖具体相位） */
    private static int probeMetadata(ObjectGroupSelector selector) {
        if (selector.specificity() == ObjectGroupSelector.Specificity.WILDCARD) {
            return 7;
        }
        return selector.metadata().get(0).intValue();
    }

    /** @return 草稿里第 index 组的 modes 字符串视图 */
    private static List<String> draftModeList(DraftBuffer draft, int index) {
        List<?> groups = (List<?>) draft.getDraft(PATH);
        List<?> modes = (List<?>) ((Map<?, ?>) groups.get(index)).get("modes");
        List<String> values = new ArrayList<String>();
        for (Object mode : modes) {
            values.add(String.valueOf(mode));
        }
        return values;
    }

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
