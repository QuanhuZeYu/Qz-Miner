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

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;

/** 结构化 objectGroups 的 Draft 保存阻断与有效提交测试。 */
public class ObjectGroupConfigValidationTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-object-groups-").toFile();
        ConfigBootstrap.resetForTests();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        delete(tempDir);
    }

    @Test
    public void invalidSelectorBlocksDraftSaveAndPreservesAuthority() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.objectGroups", groups(group("logs", "minecraft:log@2147483648")));
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(SaveOutcome.Status.INVALID, outcome.status());
        // 只断言「authority 仍是出厂默认」这一行为，不冻结默认组数（默认对象组是随 issue 演进的列表）。
        Assert.assertEquals("authority 必须保持出厂默认而不是被非法草稿污染",
                QzMinerConfigDefaults.objectGroups(), manager.authority().get("client.objectGroups"));
        Assert.assertFalse("出厂默认不得为空",
                ((List<?>) manager.authority().get("client.objectGroups")).isEmpty());
    }

    @Test
    public void overlappingSharedModeBlocksBothDraftPaths() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        DraftBuffer draft = manager.openDraft();
        Map<String, Object> first = group("a", "minecraft:log@*");
        Map<String, Object> second = group("b", "minecraft:log@0");
        first.put("modes", Arrays.asList("chain_base"));
        second.put("modes", Arrays.asList("chain_base"));
        draft.setDraft("client.objectGroups", groups(first, second));
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(SaveOutcome.Status.INVALID, outcome.status());
        Assert.assertTrue(outcome.validation().errors().containsKey("client.objectGroups[0].modes"));
        Assert.assertTrue(outcome.validation().errors().containsKey("client.objectGroups[1].modes"));
    }

    @Test
    public void validStructuredObjectGroupsCommitAsImmutableRuleSet() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.objectGroups", groups(group("logs", "minecraft:log@[0,4,8,12]")));
        SaveOutcome outcome = manager.save(draft);
        Assert.assertTrue(outcome.isSuccess());
        ConfigBootstrap.captureCommittedSnapshot(manager);
        Assert.assertEquals("logs", ConfigBootstrap.currentValidatedSnapshot().objectGroups.groups().get(0).id());
    }

    @Test
    public void fullIntMetadataCommitsWithoutChangingTheStructuredSchema() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.objectGroups", groups(group("extended",
                "minecraft:log@[16,24902,65535,16777216,2147483647]")));

        SaveOutcome outcome = manager.save(draft);

        Assert.assertTrue(outcome.isSuccess());
        ConfigBootstrap.captureCommittedSnapshot(manager);
        Assert.assertTrue(ConfigBootstrap.currentValidatedSnapshot().objectGroups.groups().get(0)
                .matches("minecraft:log", Integer.MAX_VALUE));
    }

    /**
     * 草稿层单字段重置路径：必须回到 schema 真源（= {@link QzMinerConfigDefaults#objectGroups()}）。
     *
     * <p>断言口径：重置结果与真源全等（顺序/modes/members），并逐组证明「可用」——默认组是随 issue
     * 演进的覆盖列表，因此这里不冻结组数/成员数。</p>
     */
    @Test
    public void resetFieldToDefaultRestoresShippedDefaultGroups() {
        DraftBuffer draft = ConfigBootstrap.bootstrap(tempDir, null).openDraft();
        draft.setDraft("client.objectGroups", new ArrayList<Object>());

        draft.resetFieldToDefault("client.objectGroups");

        Assert.assertEquals("重置必须回到唯一真源",
                QzMinerConfigDefaults.objectGroups(), draft.getDraft("client.objectGroups"));
        List<?> restored = (List<?>) draft.getDraft("client.objectGroups");
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(restored);
        Assert.assertTrue("重置结果必须可解析: " + parsed.error(), parsed.isValid());
        for (ObjectGroup group : parsed.rules().groups()) {
            Assert.assertNotEquals("重置后的组不得是未生效态（模式位非 0）: " + group.id(),
                    0L, group.modeMask());
            Assert.assertFalse("重置后的组 members 不得为空: " + group.id(), group.members().isEmpty());
        }
    }


    private static List<Map<String, Object>> groups(Map<String, Object>... groups) {
        return new ArrayList<Map<String, Object>>(Arrays.asList(groups));
    }

    private static Map<String, Object> group(String id, String member) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("members", new ArrayList<String>(Arrays.asList(member)));
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
