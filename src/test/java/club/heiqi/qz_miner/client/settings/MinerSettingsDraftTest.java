package club.heiqi.qz_miner.client.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import club.heiqi.qz_miner.config.ConfigSemanticValidator;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

public class MinerSettingsDraftTest {
    @Test public void remoteAndNonAdminCannotMutateServerOrToolPolicy() {
        for (MinerSettingsAccess access : Arrays.asList(MinerSettingsAccess.REMOTE,
                MinerSettingsAccess.INTEGRATED_READ_ONLY)) {
            MinerSettingsDraft draft = draft(access);
            for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
                if (!MinerSettingsAccess.serverControlled(key)) continue;
                try { draft.set(key, key.defaultValue()); Assert.fail(key.name()); }
                catch (IllegalStateException expected) { Assert.assertFalse(draft.dirty()); }
            }
            draft.set(QzMinerConfigSchema.HUD_ENABLED, false);
            Assert.assertTrue(draft.validate().valid());
        }
    }

    @Test public void invalidRawInputAndCrossKeyErrorsRemainEditable() {
        MinerSettingsDraft draft = draft(MinerSettingsAccess.MENU);
        draft.set(QzMinerConfigSchema.CHAIN_RADIUS, "12.6");
        draft.set(QzMinerConfigSchema.CLIENT_PREVIEW_ALPHA_START_VALUE, "0.1");
        draft.set(QzMinerConfigSchema.CLIENT_PREVIEW_ALPHA_END_VALUE, "0.9");
        Assert.assertFalse(draft.validate().valid());
        Assert.assertEquals("12.6", draft.value(QzMinerConfigSchema.CHAIN_RADIUS));
        draft.set(QzMinerConfigSchema.CHAIN_RADIUS, "32");
        Assert.assertTrue(draft.validate().errors.containsKey("client.clientPreviewAlphaEndValue"));
        draft.set(QzMinerConfigSchema.CLIENT_PREVIEW_ALPHA_END_VALUE, "0.05");
        Assert.assertTrue(draft.validate().valid());
    }

    @Test public void unknownRegistryAndMetadataSetUseRealBusinessParser() {
        MinerSettingsDraft draft = draft(MinerSettingsAccess.MENU);
        draft.set(QzMinerConfigSchema.OBJECT_GROUPS,
                Collections.singletonList(group("unknown", "missing_mod:not_registered@[12,0,4]")));
        MinerSettingsDraft.Validation result = draft.validate();
        Assert.assertTrue(result.errors.toString(), result.valid());
        Assert.assertEquals("missing_mod:not_registered@[0,4,12]", ConfigSemanticValidator.validate(result.candidate)
                .snapshot.objectGroups.groups().get(0).members().get(0).canonical());
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("left", "missing_mod:not_registered@*"));
        groups.add(group("right", "missing_mod:not_registered@4"));
        draft.set(QzMinerConfigSchema.OBJECT_GROUPS, groups);
        Assert.assertFalse(draft.validate().valid());
    }

    @Test public void aggregateWireLimitIsEnforcedBeforeSubmission() {
        MinerSettingsDraft draft = draft(MinerSettingsAccess.MENU);
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 16; i++) {
            Map<String, Object> group = group("g" + i, "mod:x@0");
            List<String> members = new ArrayList<String>();
            for (int j = 0; j < 128; j++) members.add("unregistered_mod:long_registry_name_" + i + "_" + j + "@0");
            group.put("members", members);
            groups.add(group);
        }
        draft.set(QzMinerConfigSchema.OBJECT_GROUPS, groups);
        Assert.assertFalse(draft.validate().valid());
        Assert.assertTrue(draft.validate().errors.containsKey("client.objectGroups"));
    }

    private static MinerSettingsDraft draft(MinerSettingsAccess access) {
        return new MinerSettingsDraft(ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA), access);
    }
    private static Map<String, Object> group(String id, String member) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("modes", Collections.singletonList("chain_base"));
        result.put("members", Collections.singletonList(member));
        return result;
    }
}
