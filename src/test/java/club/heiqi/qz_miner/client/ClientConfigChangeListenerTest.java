package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch.Mailbox;

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
    public void replacementClosesOldQueuedTaskAndSeedsCurrentIntoNewMailbox() {
        ListenerHarness old = new ListenerHarness(manager);
        ListenerHarness replacement = new ListenerHarness(manager);
        old.listener.register();
        saveRadius(23);
        Assert.assertEquals(1, old.clientDispatcher.tasks.size());

        replacement.listener.register();
        Assert.assertEquals("replacement must seed the already committed current wrapper",
                1, replacement.clientDispatcher.tasks.size());
        replacement.clientDispatcher.runAt(0);
        old.clientDispatcher.runAt(0);

        Assert.assertEquals(0, old.publicationCount.get());
        Assert.assertEquals(0, old.chainStateWriteCount.get());
        Assert.assertEquals(0, old.networkSendCount.get());
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

    private static final class ListenerHarness {
        private final QueueDispatcher clientDispatcher = new QueueDispatcher();
        private final QueueDispatcher serverDispatcher = new QueueDispatcher();
        private final AtomicInteger publicationCount = new AtomicInteger();
        private final AtomicInteger chainStateWriteCount = new AtomicInteger();
        private final AtomicInteger networkSendCount = new AtomicInteger();
        private final ClientConfigChangeListener listener;

        private ListenerHarness(ConfigManager manager) {
            Mailbox clientMailbox = new Mailbox(
                    "test-client",
                    clientDispatcher,
                    new ConfigSnapshotDispatch.Publication() {
                        @Override
                        public void publish(ValidatedSnapshot snapshot) {
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
