package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch.Mailbox;
import club.heiqi.qz_miner.config.ConfigValueBridge;

/** ConfigManager、listener 与 mailbox 的精确订阅生命周期。 */
public class ClientConfigChangeListenerTest {

    private File tempDir;
    private ConfigManager manager;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-listener-").toFile();
        ClientConfigChangeListener.resetSubscriptionForTests();
        ConfigBootstrap.resetForTests();
        manager = ConfigBootstrap.bootstrap(tempDir, null);
    }

    @After
    public void tearDown() {
        ClientConfigChangeListener.resetSubscriptionForTests();
        ConfigBootstrap.resetForTests();
        deleteRecursively(tempDir);
    }

    @Test
    public void initialRegisterDoesNotSeedDuplicatePublicationAndRepeatIsIdempotent() {
        ListenerHarness harness = new ListenerHarness(manager);

        harness.listener.register();
        harness.listener.register();

        Assert.assertEquals(0, harness.clientDispatcher.tasks.size());
        Assert.assertEquals(0, harness.serverDispatcher.tasks.size());
    }

    @Test
    public void replacementClosesOldQueuedTaskAndRecapturesAuthorityIntoNewMailbox() {
        ListenerHarness old = new ListenerHarness(manager);
        ListenerHarness replacement = new ListenerHarness(manager);
        old.listener.register();
        saveRadius(23);
        Assert.assertEquals(1, old.clientDispatcher.tasks.size());
        long epochBeforeReplacement = ConfigBootstrap.currentCommittedSnapshot().epoch;

        replacement.listener.register();
        Assert.assertEquals("replacement must recapture Authority into the new mailbox",
                1, replacement.clientDispatcher.tasks.size());
        // recapture 产生新 epoch，不小于替换前 current
        Assert.assertTrue(ConfigBootstrap.currentCommittedSnapshot().epoch >= epochBeforeReplacement);
        replacement.clientDispatcher.runAt(0);
        old.clientDispatcher.runAt(0);

        Assert.assertEquals(0, old.publicationCount.get());
        Assert.assertEquals(0, old.chainStateWriteCount.get());
        Assert.assertEquals(0, old.networkSendCount.get());
        Assert.assertEquals(1, replacement.publicationCount.get());
    }

    @Test
    public void reloadDraftFromDiskQueuesReloadAndReplaysCommittedSnapshot() throws Exception {
        ListenerHarness harness = new ListenerHarness(manager);
        harness.listener.register();
        final List<ConfigChangeEvent.ChangeType> eventTypes = new ArrayList<ConfigChangeEvent.ChangeType>();
        final List<ValidatedSnapshot> eventSnapshots = new ArrayList<ValidatedSnapshot>();
        manager.eventBus().subscribe(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                eventTypes.add(event.getType());
                eventSnapshots.add(ConfigBootstrap.currentValidatedSnapshot());
            }
        });

        String yaml = new String(
                Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()), StandardCharsets.UTF_8);
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                replaceYamlScalar(replaceYamlScalar(yaml, "clientEnablePreviewRender", "false"),
                        "autoToolTakeoverEnabled", "false")
                        .getBytes(StandardCharsets.UTF_8));

        Assert.assertNotNull(manager.reloadDraftFromDisk());
        Assert.assertEquals(java.util.Collections.singletonList(ConfigChangeEvent.ChangeType.RELOAD), eventTypes);
        Assert.assertEquals(1, harness.clientDispatcher.tasks.size());

        ValidatedSnapshot current = ConfigBootstrap.currentValidatedSnapshot();
        Assert.assertFalse(current.clientEnablePreviewRender);
        Assert.assertFalse(current.autoToolTakeoverEnabled);
        Assert.assertEquals(Boolean.FALSE, manager.authority().get("client.clientEnablePreviewRender"));
        Assert.assertEquals(1, eventSnapshots.size());
        Assert.assertSame(current, eventSnapshots.get(0));
        // RELOAD 通知期只捕获 Authority；客户端 runtime 必须等 mailbox 回灌。
        Assert.assertTrue(Config.clientEnablePreviewRender);

        harness.clientDispatcher.runAt(0);

        Assert.assertSame(current, harness.lastPublishedSnapshot);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertFalse(Config.autoToolTakeoverEnabled);
        Assert.assertEquals(current.clientEnablePreviewRender, harness.lastPublishedSnapshot.clientEnablePreviewRender);
        Assert.assertEquals(current.clientPreviewMaxRadius, Config.clientPreviewMaxRadius);
    }

    @Test
    public void nonTargetChangeEventsAreIgnoredWithoutCaptureOrDispatch() {
        ListenerHarness harness = new ListenerHarness(manager);
        harness.listener.register();
        long epochBefore = ConfigBootstrap.currentCommittedSnapshot().epoch;
        boolean runtimeBefore = Config.clientEnablePreviewRender;

        harness.listener.onConfigChanged(new ConfigChangeEvent(
                "client.clientEnablePreviewRender",
                Boolean.TRUE,
                Boolean.FALSE,
                ConfigChangeEvent.ChangeType.SET));
        harness.listener.onConfigChanged(new ConfigChangeEvent(
                "",
                null,
                null,
                ConfigChangeEvent.ChangeType.CLEAR));
        harness.listener.onConfigChanged(null);

        Assert.assertEquals(0, harness.clientDispatcher.tasks.size());
        Assert.assertEquals(0, harness.serverDispatcher.tasks.size());
        Assert.assertEquals(epochBefore, ConfigBootstrap.currentCommittedSnapshot().epoch);
        Assert.assertEquals(runtimeBefore, Config.clientEnablePreviewRender);
    }

    /**
     * 覆盖 COW 交接窗口：保存已成功但 current 尚未 capture 时，replacement 仍能捕获 Authority。
     *
     * <p>边界：UILib event bus 的精确 COW 快照屏障无法从 Miner 测试钩住；
     * 本测试用“无 active listener 时 save 写 Authority、current 保持旧 epoch，
     * 再经 replacement register 锁内 recapture”模拟最接近的可控场景。</p>
     */
    @Test
    public void replacementRecapturesSavedAuthorityEvenWhenCurrentWasNotYetCaptured() {
        ListenerHarness seedHolder = new ListenerHarness(manager);
        seedHolder.listener.register();
        long epochBeforeSave = ConfigBootstrap.currentCommittedSnapshot().epoch;
        // 卸下 listener，使后续 save 不经 BATCH_SAVE capture
        ClientConfigChangeListener.resetSubscriptionForTests();
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(77));
        Assert.assertTrue(manager.save(draft).isSuccess());
        Assert.assertEquals(epochBeforeSave, ConfigBootstrap.currentCommittedSnapshot().epoch);

        // 先占位订阅（不 recapture），再 replacement 触发锁内 captureCommittedSnapshot
        ListenerHarness first = new ListenerHarness(manager);
        first.listener.register();
        Assert.assertEquals(0, first.clientDispatcher.tasks.size());

        ListenerHarness replacement = new ListenerHarness(manager);
        replacement.listener.register();

        Assert.assertEquals(1, replacement.clientDispatcher.tasks.size());
        Assert.assertTrue(
                "replacement recapture must advance current past the pre-save epoch",
                ConfigBootstrap.currentCommittedSnapshot().epoch > epochBeforeSave);
        Assert.assertEquals(77, ConfigBootstrap.currentValidatedSnapshot().chainRadius);
        replacement.clientDispatcher.runAt(0);
        Assert.assertEquals(1, replacement.publicationCount.get());
    }

    @Test
    public void resetClosesCurrentAndReverseQueuedExecutionIsNoOp() {
        ListenerHarness old = new ListenerHarness(manager);
        ListenerHarness replacement = new ListenerHarness(manager);
        old.listener.register();
        saveRadius(24);
        replacement.listener.register();
        ClientConfigChangeListener.resetSubscriptionForTests();

        replacement.clientDispatcher.runAt(0);
        old.clientDispatcher.runAt(0);

        Assert.assertEquals(0, old.publicationCount.get());
        Assert.assertEquals(0, old.chainStateWriteCount.get());
        Assert.assertEquals(0, old.networkSendCount.get());
        Assert.assertEquals(0, replacement.publicationCount.get());
        Assert.assertEquals(0, replacement.chainStateWriteCount.get());
        Assert.assertEquals(0, replacement.networkSendCount.get());
    }

    @Test
    public void oldListenerCallbackAfterReplacementCannotCaptureWrongManager() {
        ListenerHarness old = new ListenerHarness(manager);
        ListenerHarness replacement = new ListenerHarness(manager);
        old.listener.register();
        replacement.listener.register();

        saveRadius(25);

        Assert.assertEquals(0, old.clientDispatcher.tasks.size());
        Assert.assertEquals(1, replacement.clientDispatcher.tasks.size());
    }

    @Test
    public void oldManagerCallbackDuringBootstrapReplacementIsDiscardedBeforeFatalCapture() throws Exception {
        ConfigManager oldManager = manager;
        ListenerHarness old = new ListenerHarness(oldManager);
        old.listener.register();
        ConfigBootstrap.resetForTests();
        File replacementDir = new File(tempDir, "replacement");
        manager = ConfigBootstrap.bootstrap(replacementDir, null);

        DraftBuffer staleDraft = oldManager.openDraft();
        staleDraft.setDraft("general.chainRadius", Double.valueOf(26));
        Assert.assertTrue(oldManager.save(staleDraft).isSuccess());

        Assert.assertEquals(0, old.clientDispatcher.tasks.size());
        ListenerHarness replacement = new ListenerHarness(manager);
        replacement.listener.register();
        Assert.assertEquals(1, replacement.clientDispatcher.tasks.size());
    }

    private void saveRadius(int radius) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(radius));
        Assert.assertTrue(manager.save(draft).isSuccess());
    }

    private static String replaceYamlScalar(String yaml, String field, String replacement) {
        String prefix = "  " + field + ": ";
        String[] lines = yaml.split("\\n", -1);
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) {
                lines[i] = prefix + replacement;
                replaced = true;
                break;
            }
        }
        Assert.assertTrue("missing YAML field: " + field, replaced);
        StringBuilder result = new StringBuilder(yaml.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(lines[i]);
        }
        return result.toString();
    }

    private static final class ListenerHarness {
        private final QueueDispatcher clientDispatcher = new QueueDispatcher();
        private final QueueDispatcher serverDispatcher = new QueueDispatcher();
        private final AtomicInteger publicationCount = new AtomicInteger();
        private final AtomicInteger chainStateWriteCount = new AtomicInteger();
        private final AtomicInteger networkSendCount = new AtomicInteger();
        private ValidatedSnapshot lastPublishedSnapshot;
        private final ClientConfigChangeListener listener;

        private ListenerHarness(ConfigManager manager) {
            Mailbox clientMailbox = new Mailbox(
                    "test-client",
                    clientDispatcher,
                    new ConfigSnapshotDispatch.Publication() {
                        @Override
                        public void publish(ValidatedSnapshot snapshot) {
                            ConfigValueBridge.applyClientFromSnapshot(snapshot);
                            lastPublishedSnapshot = snapshot;
                            publicationCount.incrementAndGet();
                            chainStateWriteCount.incrementAndGet();
                            networkSendCount.incrementAndGet();
                        }
                    });
            Mailbox serverMailbox = new Mailbox(
                    "test-server",
                    serverDispatcher,
                    new ConfigSnapshotDispatch.Publication() {
                        @Override
                        public void publish(ValidatedSnapshot snapshot) {
                            publicationCount.incrementAndGet();
                        }
                    });
            listener = new ClientConfigChangeListener(
                    manager,
                    clientMailbox,
                    serverMailbox,
                    new ClientConfigChangeListener.IntegratedServerProbe() {
                        @Override
                        public boolean isRunning() {
                            return false;
                        }
                    });
        }
    }

    private static final class QueueDispatcher implements ConfigSnapshotDispatch.Dispatcher {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public boolean dispatch(Runnable task) {
            tasks.add(task);
            return true;
        }

        void runAt(int index) {
            tasks.get(index).run();
        }
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
