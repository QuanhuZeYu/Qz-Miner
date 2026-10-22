package club.heiqi.qz_miner.config;

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

import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.uilib.ui.config.ConfigDocument;

/** 真实对象组候选的校验阻断与持久提交。 */
public class ObjectGroupConfigValidationTest {
    private File directory;

    @Before
    public void setUp() throws Exception {
        ConfigTestSupport.reset();
        directory = Files.createTempDirectory("qz-objectgroups-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
    }
    @After
    public void tearDown() {
        ConfigTestSupport.reset();
        delete(directory);
    }

    @Test
    public void invalidSelectorCannotChangeDocumentDiskOrRevision() throws Exception {
        ConfigDocument before = ConfigBootstrap.document();
        CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        try {
            ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups",
                    groups(group("logs", "minecraft:log@2147483648"))));
            Assert.fail("metadata 超界必须拒绝");
        } catch (RuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("client.objectGroups"));
        }
        Assert.assertSame(before, ConfigBootstrap.document());
        Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
    }

    @Test
    public void overlappingSharedModeMarksBothGroupPaths() {
        Map<String, Object> first = group("a", "minecraft:log@*");
        Map<String, Object> second = group("b", "minecraft:log@0");
        ConfigDocument candidate = ConfigBootstrap.document().withAll(
                ConfigTestSupport.changes("client.objectGroups", groups(first, second)));
        ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.validate(candidate);
        Assert.assertFalse(outcome.isValid());
        Assert.assertTrue(outcome.result.errors().containsKey("client.objectGroups[0].modes"));
        Assert.assertTrue(outcome.result.errors().containsKey("client.objectGroups[1].modes"));
    }

    @Test
    public void fullIntMetadataAndCanonicalGroupsSurviveDiskReload() throws Exception {
        List<Map<String, Object>> source = groups(group("扩展组",
                "minecraft:log@[2147483647,16,24902,65535,16777216]"));
        CommittedSnapshot committed = ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups", source));
        Assert.assertTrue(committed.snapshot.objectGroups.groups().get(0).matches("minecraft:log", Integer.MAX_VALUE));
        String canonical = committed.snapshot.objectGroups.groups().get(0).members().get(0).canonical();
        Assert.assertEquals("minecraft:log@[16,24902,65535,16777216,2147483647]", canonical);
        source.get(0).put("id", "调用者之后修改");
        Assert.assertEquals("扩展组", ConfigBootstrap.document().get(QzMinerConfigSchema.OBJECT_GROUPS).get(0).get("id"));
        CommittedSnapshot reloaded = ConfigBootstrap.reload();
        Assert.assertEquals("扩展组", reloaded.snapshot.objectGroups.groups().get(0).id());
        Assert.assertEquals(canonical, reloaded.snapshot.objectGroups.groups().get(0).members().get(0).canonical());
    }

    @Test
    public void objectCodecRejectsMissingMembersAndWrongNestedTypesBeforeCommit() throws Exception {
        Map<String, Object> missing = group("a", "minecraft:log@0");
        missing.remove("members");
        Map<String, Object> wrongType = group("a", "minecraft:log@0");
        wrongType.put("modes", Arrays.asList(1));
        for (Map<String, Object> invalid : Arrays.asList(missing, wrongType)) {
            ConfigDocument before = ConfigBootstrap.document();
            try {
                ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups", groups(invalid)));
                Assert.fail("结构化对象字段必须严格校验");
            } catch (RuntimeException expected) {
                Assert.assertSame(before, ConfigBootstrap.document());
            }
        }
    }

    @Test
    public void replacingAValueWithItsDeclaredDefaultRestoresActiveShippedRules() throws Exception {
        ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups", new ArrayList<Object>()));
        ConfigBootstrap.commit(java.util.Collections.singletonMap(QzMinerConfigSchema.OBJECT_GROUPS,
                QzMinerConfigSchema.OBJECT_GROUPS.defaultValue()));
        Assert.assertEquals(QzMinerConfigDefaults.objectGroups(), ConfigBootstrap.document().get(QzMinerConfigSchema.OBJECT_GROUPS));
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(ConfigBootstrap.document().get(QzMinerConfigSchema.OBJECT_GROUPS));
        Assert.assertTrue(parsed.isValid());
        for (club.heiqi.qz_miner.objectgroup.ObjectGroup group : parsed.rules().groups()) {
            Assert.assertNotEquals(0L, group.modeMask());
            Assert.assertFalse(group.members().isEmpty());
        }
    }

    @Test
    public void missingModesInRealYamlKeepsWholeDoubleRadiusAndAnInactiveLegalGroup() throws Exception {
        File yaml = ConfigBootstrap.yamlFile();
        String input = "general:\n  chainRadius: 32.0\nclient:\n  objectGroups:\n"
                + "    - id: inactive\n      members: ['minecraft:stone@0']\n";
        Files.write(yaml.toPath(), input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ConfigTestSupport.reset();
        ConfigDocument document = ConfigBootstrap.bootstrap(directory, null);
        Assert.assertEquals(32, ConfigBootstrap.currentValidatedSnapshot().chainRadius);
        Assert.assertEquals(Double.valueOf(32.0), document.get(QzMinerConfigSchema.CHAIN_RADIUS));
        Map<String, Object> group = document.get(QzMinerConfigSchema.OBJECT_GROUPS).get(0);
        Assert.assertEquals("inactive", group.get("id"));
        Assert.assertEquals(java.util.Collections.emptyList(), group.get("modes"));
        Assert.assertEquals(0L, ConfigBootstrap.currentValidatedSnapshot().objectGroups.groups().get(0).modeMask());
        Assert.assertEquals(input, new String(Files.readAllBytes(yaml.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        Assert.assertEquals(1, directory.listFiles().length);

        ConfigDocument before = ConfigBootstrap.document();
        CommittedSnapshot current = ConfigBootstrap.currentCommittedSnapshot();
        Files.write(yaml.toPath(), input.replace("      members:", "      modes: null\n      members:")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            ConfigBootstrap.reload();
            Assert.fail("显式 null 不等于缺失 modes");
        } catch (club.heiqi.uilib.ui.config.ConfigException expected) {
            Assert.assertEquals("client.objectGroups[0].modes", expected.path());
        }
        Assert.assertSame(before, ConfigBootstrap.document());
        Assert.assertSame(current, ConfigBootstrap.currentCommittedSnapshot());
    }

    @Test
    public void wireCapacityIsValidatedBeforeCandidateReachesDiskOrObservers() throws Exception {
        List<Map<String, Object>> atLimit = payloadGroups(126, 228);
        ObjectGroupParser.ParseResult accepted = ObjectGroupParser.parse(atLimit);
        Assert.assertTrue(accepted.error(), accepted.isValid());
        club.heiqi.qz_miner.network.ObjectGroupWireConfig wire =
                club.heiqi.qz_miner.network.ObjectGroupWireConfig.fromRuleSet(1L, accepted.rules());
        Assert.assertEquals(club.heiqi.qz_miner.network.ObjectGroupWireConfig.MAX_PAYLOAD_BYTES, wire.encodedSize());
        CommittedSnapshot committed = ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups", atLimit));
        ConfigDocument before = ConfigBootstrap.document();
        byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        List<CommittedSnapshot> notifications = new ArrayList<CommittedSnapshot>();
        ConfigBootstrap.subscribe(notifications::add);

        // 首个候选只增加一个 ASCII byte；第二个重现所有单项合法却总 payload 超限的评审输入。
        for (List<Map<String, Object>> tooLarge : Arrays.asList(payloadGroups(126, 229), payloadGroups(128, 251))) {
            ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(tooLarge);
            Assert.assertTrue(parsed.error(), parsed.isValid());
            try {
                club.heiqi.qz_miner.network.ObjectGroupWireConfig.fromRuleSet(committed.epoch, parsed.rules());
                Assert.fail("真实 wire 编码必须拒绝总容量超限");
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("32KiB"));
            }
            try {
                ConfigBootstrap.commit(ConfigTestSupport.changes("client.objectGroups", tooLarge));
                Assert.fail("不可传输的业务候选必须在落盘与发布前拒绝");
            } catch (club.heiqi.uilib.ui.config.ConfigException expected) {
                Assert.assertEquals("client.objectGroups", expected.path());
            }
            Assert.assertSame(before, ConfigBootstrap.document());
            Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
            Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
            Assert.assertTrue(notifications.isEmpty());
        }
    }

    private static List<Map<String, Object>> payloadGroups(int count, int lastPadding) {
        List<String> members = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            int padding = i == count - 1 ? lastPadding : 251;
            char[] text = new char[padding];
            Arrays.fill(text, 'a');
            members.add("x:" + new String(text) + String.format(java.util.Locale.ROOT, "%03d", i) + "@*");
        }
        Map<String, Object> group = group("a", "unused:placeholder@*");
        group.put("members", members);
        return groups(group);
    }

    @SafeVarargs
    private static List<Map<String, Object>> groups(Map<String, Object>... values) {
        return new ArrayList<Map<String, Object>>(Arrays.asList(values));
    }
    private static Map<String, Object> group(String id, String member) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", Arrays.asList("chain_base"));
        group.put("members", Arrays.asList(member));
        return group;
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
