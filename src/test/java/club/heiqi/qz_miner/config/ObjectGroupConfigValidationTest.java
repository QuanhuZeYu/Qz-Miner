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
        draft.setDraft("client.objectGroups", groups(group("logs", "minecraft:log@16")));
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(SaveOutcome.Status.INVALID, outcome.status());
        Assert.assertEquals(3, ((List<?>) manager.authority().get("client.objectGroups")).size());
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
    public void beta12ResetRestoresRealSchemaObjectGroupDefaults() {
        DraftBuffer draft = ConfigBootstrap.bootstrap(tempDir, null).openDraft();
        draft.setDraft("client.objectGroups", new ArrayList<Object>());

        draft.resetFieldToDefault("client.objectGroups");

        Assert.assertEquals(QzMinerConfigDefaults.objectGroups(), draft.getDraft("client.objectGroups"));
        List<?> restored = (List<?>) draft.getDraft("client.objectGroups");
        Assert.assertEquals(Arrays.asList("vanilla_logs", "vanilla_hay", "vanilla_redstone"),
                Arrays.asList(id(restored, 0), id(restored, 1), id(restored, 2)));
        Assert.assertEquals(Arrays.asList("minecraft:log@*", "minecraft:log2@*"), members(restored, 0));
        Assert.assertEquals(Arrays.asList("minecraft:hay_block@[0,4,8]"), members(restored, 1));
        Assert.assertEquals(Arrays.asList("minecraft:redstone_ore@*", "minecraft:lit_redstone_ore@*"),
                members(restored, 2));
        for (Object value : restored) Assert.assertEquals(new ArrayList<Object>(), ((Map<?, ?>) value).get("modes"));
    }

    private static Object id(List<?> groups, int index) {
        return ((Map<?, ?>) groups.get(index)).get("id");
    }

    private static Object members(List<?> groups, int index) {
        return ((Map<?, ?>) groups.get(index)).get("members");
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
