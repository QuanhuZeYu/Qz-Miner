package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/** 提交快照缓存、生命周期与异步闭包一致性。 */
public class ConfigSnapshotDispatchTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-snapshot-").toFile();
        ConfigBootstrap.resetForTests();
        Config.chainRadius = QzMinerConfigDefaults.CHAIN_RADIUS;
        Config.chainMaxBlocks = QzMinerConfigDefaults.CHAIN_MAX_BLOCKS;
        Config.clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        deleteRecursively(tempDir);
    }

    @Test
    public void consecutiveSavesCannotRollCurrentCacheBackWhenOldTaskRunsLate() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Harness harness = new Harness(manager, false);
        manager.eventBus().subscribe(harness);

        saveRadius(manager, 12);
        ValidatedSnapshot first = harness.captured.get(0);
        saveRadius(manager, 24);
        ValidatedSnapshot second = harness.captured.get(1);

        Assert.assertSame(second, ConfigBootstrap.currentValidatedSnapshot());
        harness.client.runAt(1);
        harness.client.runAt(0);
        Assert.assertSame("late old task must not replace current cache",
                second, ConfigBootstrap.currentValidatedSnapshot());
        Assert.assertSame(second, harness.clientPublished.get(0));
        Assert.assertEquals("late old client publication must be skipped", 1, harness.clientPublished.size());
    }

    @Test
    public void mainMenuSaveIsAppliedToGeneralAtServerStarting() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Harness harness = new Harness(manager, false);
        manager.eventBus().subscribe(harness);

        saveRadius(manager, 31);
        Assert.assertEquals("main menu callback must not write general", QzMinerConfigDefaults.CHAIN_RADIUS,
                Config.chainRadius);

        ConfigBootstrap.reapplyGeneralOnServerStarting();
        Assert.assertEquals(31, Config.chainRadius);
    }

    @Test
    public void remoteClientPublishesClientOnlyAndRequestValuesComeFromSnapshot() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Harness harness = new Harness(manager, false);
        manager.eventBus().subscribe(harness);

        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(40.0D));
        draft.setDraft("general.chainMaxBlocks", Double.valueOf(400.0D));
        draft.setDraft("client.clientPreviewMaxRadius", Double.valueOf(9.0D));
        Assert.assertTrue(manager.save(draft).isSuccess());

        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(0, harness.server.tasks.size());
        harness.client.runAt(0);
        Assert.assertEquals(9, Config.clientPreviewMaxRadius);
        Assert.assertEquals(40, ConfigBootstrap.currentValidatedSnapshot().chainRadius);
        Assert.assertEquals(400, ConfigBootstrap.currentValidatedSnapshot().chainMaxBlocks);
        Assert.assertEquals("remote general static remains server-owned",
                QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
    }

    @Test
    public void clientAndServerAsyncLambdasCaptureSameSnapshotReference() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Harness harness = new Harness(manager, true);
        manager.eventBus().subscribe(harness);

        saveRadius(manager, 18);
        ValidatedSnapshot committed = ConfigBootstrap.currentValidatedSnapshot();
        harness.client.runAt(0);
        harness.server.runAt(0);

        Assert.assertSame(committed, harness.clientPublished.get(0));
        Assert.assertSame(committed, harness.serverPublished.get(0));
        Assert.assertEquals(18, Config.chainRadius);
    }

    @Test
    public void clientAndServerBothRejectOlderSnapshotWhenTasksRunInReverseOrder() {
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        Harness harness = new Harness(manager, true);
        manager.eventBus().subscribe(harness);

        saveRadiusAndClientPreview(manager, 14, 7);
        saveRadiusAndClientPreview(manager, 28, 11);
        ValidatedSnapshot second = harness.captured.get(1);

        harness.client.runAt(1);
        harness.server.runAt(1);
        harness.client.runAt(0);
        harness.server.runAt(0);

        Assert.assertEquals(11, Config.clientPreviewMaxRadius);
        Assert.assertEquals(28, Config.chainRadius);
        Assert.assertEquals(1, harness.clientPublished.size());
        Assert.assertEquals(1, harness.serverPublished.size());
        Assert.assertSame(second, harness.clientPublished.get(0));
        Assert.assertSame("same current snapshot must feed both sides", second, harness.serverPublished.get(0));
    }

    @Test
    public void invalidServerArgumentsAreRejectedBeforeClientIsQueued() {
        ConfigBootstrap.bootstrap(tempDir, null);
        ValidatedSnapshot current = ConfigBootstrap.currentValidatedSnapshot();
        QueueDispatcher client = new QueueDispatcher();
        try {
            ConfigSnapshotDispatch.dispatch(current, client, recordingPublication(new ArrayList<ValidatedSnapshot>()),
                    true, null, recordingPublication(new ArrayList<ValidatedSnapshot>()));
            Assert.fail("missing server dispatcher must fail preflight");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("server"));
        }
        Assert.assertEquals("full preflight must happen before any partial queueing", 0, client.tasks.size());
    }

    @Test
    public void serverDispatcherFailureKeepsAcceptedClientTaskAsBestEffortAndPropagates() {
        ConfigBootstrap.bootstrap(tempDir, null);
        ValidatedSnapshot current = ConfigBootstrap.currentValidatedSnapshot();
        QueueDispatcher client = new QueueDispatcher();
        final List<ValidatedSnapshot> published = new ArrayList<ValidatedSnapshot>();
        try {
            ConfigSnapshotDispatch.dispatch(current, client, recordingPublication(published), true,
                    new ThrowingDispatcher(), recordingPublication(new ArrayList<ValidatedSnapshot>()));
            Assert.fail("dispatcher failure must propagate");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("forced dispatcher failure", expected.getMessage());
        }
        Assert.assertEquals("accepted client task cannot be rolled back", 1, client.tasks.size());
        client.runAt(0);
        Assert.assertSame(current, published.get(0));
    }

    private static void saveRadius(ConfigManager manager, int radius) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(radius));
        Assert.assertTrue(manager.save(draft).isSuccess());
    }

    private static void saveRadiusAndClientPreview(ConfigManager manager, int radius, int previewRadius) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(radius));
        draft.setDraft("client.clientPreviewMaxRadius", Double.valueOf(previewRadius));
        Assert.assertTrue(manager.save(draft).isSuccess());
    }

    private static ConfigSnapshotDispatch.Publication recordingPublication(
            final List<ValidatedSnapshot> published) {
        return new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                published.add(snapshot);
            }
        };
    }

    private static final class Harness implements ConfigChangeListener {
        private final ConfigManager manager;
        private final boolean integrated;
        private final QueueDispatcher client = new QueueDispatcher();
        private final QueueDispatcher server = new QueueDispatcher();
        private final List<ValidatedSnapshot> captured = new ArrayList<ValidatedSnapshot>();
        private final List<ValidatedSnapshot> clientPublished = new ArrayList<ValidatedSnapshot>();
        private final List<ValidatedSnapshot> serverPublished = new ArrayList<ValidatedSnapshot>();

        private Harness(ConfigManager manager, boolean integrated) {
            this.manager = manager;
            this.integrated = integrated;
        }

        @Override
        public void onConfigChanged(ConfigChangeEvent event) {
            final ValidatedSnapshot snapshot = ConfigBootstrap.captureCommittedSnapshot(manager);
            captured.add(snapshot);
            ConfigSnapshotDispatch.dispatch(
                    snapshot,
                    client,
                    new ConfigSnapshotDispatch.Publication() {
                        @Override
                        public void publish(ValidatedSnapshot value) {
                            clientPublished.add(value);
                            ConfigValueBridge.applyClientFromSnapshot(value);
                        }
                    },
                    integrated,
                    server,
                    new ConfigSnapshotDispatch.Publication() {
                        @Override
                        public void publish(ValidatedSnapshot value) {
                            serverPublished.add(value);
                            ConfigValueBridge.applyGeneralFromSnapshot(value);
                        }
                    });
        }
    }

    private static final class QueueDispatcher implements ConfigSnapshotDispatch.Dispatcher {
        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void dispatch(Runnable task) {
            tasks.add(task);
        }

        void runAt(int index) {
            tasks.get(index).run();
        }
    }

    private static final class ThrowingDispatcher implements ConfigSnapshotDispatch.Dispatcher {
        @Override
        public void dispatch(Runnable task) {
            throw new IllegalStateException("forced dispatcher failure");
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
