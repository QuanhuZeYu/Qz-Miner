package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/** DraftValidator 提交事务与零副作用断言。 */
public class ConfigSemanticValidatorTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-sem-").toFile();
        ConfigBootstrap.resetForTests();
        resetRuntimeDefaults();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        resetRuntimeDefaults();
        deleteRecursively(tempDir);
    }

    @Test
    public void nonIntegerIsInvalidAndTransactionHasZeroSideEffects() throws Exception {
        assertInvalidTransaction(new DraftMutation() {
            @Override
            public void mutate(DraftBuffer draft) {
                draft.setDraft("general.chainRadius", Double.valueOf(12.6D));
            }
        }, "general.chainRadius", Double.valueOf(12.6D));
    }

    @Test
    public void nonFiniteIsInvalidAndTransactionHasZeroSideEffects() throws Exception {
        assertInvalidTransaction(new DraftMutation() {
            @Override
            public void mutate(DraftBuffer draft) {
                draft.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(Double.NaN));
            }
        }, "client.clientPreviewAlphaStartValue", Double.valueOf(Double.NaN));
    }

    @Test
    public void crossFieldViolationIsInvalidAndTransactionHasZeroSideEffects() throws Exception {
        final String path = "client.clientPreviewAlphaFadeEndRadius";
        assertInvalidTransaction(new DraftMutation() {
            @Override
            public void mutate(DraftBuffer draft) {
                draft.setDraft("client.clientPreviewAlphaFadeStartRadius", Double.valueOf(5.0D));
                draft.setDraft(path, Double.valueOf(5.0D));
            }
        }, path, Double.valueOf(5.0D));
    }

    @Test
    public void legalSaveCommitsAndPublishesExactlyOnce() throws Exception {
        final ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        final AtomicInteger events = new AtomicInteger();
        manager.eventBus().subscribe(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                events.incrementAndGet();
                CommittedSnapshot committed = ConfigBootstrap.captureCommittedSnapshot(manager);
                ConfigValueBridge.applyAll(committed.snapshot);
            }
        });
        byte[] before = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());

        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(24.0D));
        SaveOutcome outcome = manager.save(draft);

        Assert.assertEquals(SaveOutcome.Status.OK, outcome.status());
        Assert.assertEquals(1, events.get());
        Assert.assertEquals(24, ConfigBootstrap.currentValidatedSnapshot().chainRadius);
        Assert.assertEquals(24, Config.chainRadius);
        Assert.assertEquals(Double.valueOf(24.0D), manager.authority().get("general.chainRadius"));
        Assert.assertEquals(Double.valueOf(24.0D), draft.getCurrent("general.chainRadius"));
        Assert.assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath())));
    }

    @Test
    public void autoToolSelectionRequiresExactlyOneDefaultObject() throws Exception {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Assert.assertFalse(ConfigBootstrap.currentValidatedSnapshot().autoToolSelection.enabled);
        Assert.assertEquals(2, ConfigBootstrap.currentValidatedSnapshot()
                .autoToolSelection.minimumRemainingDurability);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.autoToolSelection", new ArrayList<Object>());
        SaveOutcome outcome = manager.save(draft);
        Assert.assertEquals(SaveOutcome.Status.INVALID, outcome.status());
        Assert.assertNotNull(outcome.validation().errorFor("client.autoToolSelection"));
    }

    private void assertInvalidTransaction(DraftMutation mutation, String errorPath, Object retainedValue)
            throws Exception {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        AtomicInteger events = new AtomicInteger();
        manager.eventBus().subscribe(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                events.incrementAndGet();
            }
        });
        byte[] yamlBefore = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        Map<String, Object> authorityBefore = authorityValues(manager);
        RuntimeState runtimeBefore = RuntimeState.capture();
        ValidatedSnapshot snapshotBefore = ConfigBootstrap.currentValidatedSnapshot();

        DraftBuffer draft = manager.openDraft();
        Map<String, Object> currentBefore = currentValues(draft);
        mutation.mutate(draft);
        SaveOutcome outcome = manager.save(draft);

        Assert.assertEquals(SaveOutcome.Status.INVALID, outcome.status());
        Assert.assertNotNull(outcome.validation());
        Assert.assertNotNull("errors=" + outcome.validation().errors(), outcome.validation().errorFor(errorPath));
        assertValueEquals(retainedValue, draft.getDraft(errorPath));
        Assert.assertEquals(currentBefore, currentValues(draft));
        Assert.assertEquals(authorityBefore, authorityValues(manager));
        Assert.assertArrayEquals(yamlBefore, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        Assert.assertEquals(runtimeBefore, RuntimeState.capture());
        Assert.assertSame(snapshotBefore, ConfigBootstrap.currentValidatedSnapshot());
        Assert.assertEquals(0, events.get());
    }

    private static void assertValueEquals(Object expected, Object actual) {
        if (expected instanceof Double && ((Double) expected).isNaN()) {
            Assert.assertTrue(actual instanceof Double && ((Double) actual).isNaN());
        } else {
            Assert.assertEquals(expected, actual);
        }
    }

    private static Map<String, Object> authorityValues(ConfigManager manager) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (FieldSpec field : manager.schema().allFields()) {
            values.put(field.path(), manager.authority().get(field.path()));
        }
        return values;
    }

    private static Map<String, Object> currentValues(DraftBuffer draft) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (String path : draft.fieldPaths()) {
            values.put(path, draft.getCurrent(path));
        }
        return values;
    }

    private interface DraftMutation {
        void mutate(DraftBuffer draft);
    }

    /** 全 20 个 runtime static 的值对象（对象组规则仍由 ValidatedSnapshot 承载）。 */
    private static final class RuntimeState {
        private final List<Object> values;

        private RuntimeState(List<Object> values) {
            this.values = values;
        }

        static RuntimeState capture() {
            List<Object> values = new ArrayList<Object>();
            values.add(Config.greeting);
            values.add(Integer.valueOf(Config.chainRadius));
            values.add(Integer.valueOf(Config.chainMaxBlocks));
            values.add(Integer.valueOf(Config.chainLoggingShellLayers));
            values.add(Integer.valueOf(Config.maxBreakPerTick));
            values.add(Integer.valueOf(Config.cableReplaceMaxPerTick));
            values.add(Integer.valueOf(Config.chainWatchdogTimeoutTicks));
            values.add(Integer.valueOf(Config.parallelTickMinDurationMs));
            values.add(Integer.valueOf(Config.parallelTickServerWorkBudgetUnits));
            values.add(Boolean.valueOf(Config.enableUnlimitedOreFortune));
            values.add(Boolean.valueOf(Config.enableFortuneForPlacedOre));
            values.add(Boolean.valueOf(Config.clientEnablePreviewRender));
            values.add(Integer.valueOf(Config.parallelTickClientWorkBudgetUnits));
            values.add(Integer.valueOf(Config.clientPreviewMaxRadius));
            values.add(Integer.valueOf(Config.clientPreviewMaxTargets));
            values.add(Double.valueOf(Config.clientPreviewAlphaFadeStartRadius));
            values.add(Double.valueOf(Config.clientPreviewAlphaFadeEndRadius));
            values.add(Double.valueOf(Config.clientPreviewAlphaStartValue));
            values.add(Double.valueOf(Config.clientPreviewAlphaEndValue));
            return new RuntimeState(values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RuntimeState && values.equals(((RuntimeState) other).values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }
    }

    private static void resetRuntimeDefaults() {
        Config.greeting = QzMinerConfigDefaults.GREETING;
        Config.chainRadius = QzMinerConfigDefaults.CHAIN_RADIUS;
        Config.chainMaxBlocks = QzMinerConfigDefaults.CHAIN_MAX_BLOCKS;
        Config.chainLoggingShellLayers = QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS;
        Config.maxBreakPerTick = QzMinerConfigDefaults.MAX_BREAK_PER_TICK;
        Config.cableReplaceMaxPerTick = QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK;
        Config.chainWatchdogTimeoutTicks = QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS;
        Config.parallelTickMinDurationMs = QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS;
        Config.parallelTickServerWorkBudgetUnits = QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS;
        Config.enableUnlimitedOreFortune = QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE;
        Config.enableFortuneForPlacedOre = QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE;
        Config.clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
        Config.parallelTickClientWorkBudgetUnits = QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS;
        Config.clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
        Config.clientPreviewMaxTargets = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS;
        Config.clientPreviewAlphaFadeStartRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
        Config.clientPreviewAlphaFadeEndRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
        Config.clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
        Config.clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
