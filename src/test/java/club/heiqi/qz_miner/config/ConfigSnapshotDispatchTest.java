package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch.Mailbox;

/** 无锁 latest-wins mailbox 的确定性并发与失败恢复测试。 */
public class ConfigSnapshotDispatchTest {

    private File tempDir;
    private ConfigManager manager;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-mailbox-").toFile();
        ConfigBootstrap.resetForTests();
        manager = ConfigBootstrap.bootstrap(tempDir, null);
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        deleteRecursively(tempDir);
    }

    @Test
    public void twoSubmitsQueueOneDrainAndPublishOnlyLatest() {
        QueueDispatcher dispatcher = new QueueDispatcher();
        List<Integer> published = new ArrayList<Integer>();
        Mailbox mailbox = mailbox("client", dispatcher, published);

        CommittedSnapshot first = commitRadius(12);
        mailbox.submit(first);
        CommittedSnapshot second = commitRadius(24);
        mailbox.submit(second);

        Assert.assertEquals(1, dispatcher.tasks.size());
        dispatcher.runAt(0);
        Assert.assertEquals(Collections.singletonList(Integer.valueOf(24)), published);
        Assert.assertEquals(second.epoch, mailbox.appliedEpoch());
    }

    @Test
    public void runningPublicationFinishesThenDrainAppliesNewLatestAtDepthOne() throws Exception {
        QueueDispatcher dispatcher = new QueueDispatcher();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger depth = new AtomicInteger();
        final AtomicInteger maxDepth = new AtomicInteger();
        final List<Integer> published = Collections.synchronizedList(new ArrayList<Integer>());
        Mailbox mailbox = new Mailbox("client", dispatcher, new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                int currentDepth = depth.incrementAndGet();
                maxDepth.set(Math.max(maxDepth.get(), currentDepth));
                try {
                    published.add(Integer.valueOf(snapshot.chainRadius));
                    if (snapshot.chainRadius == 12) {
                        entered.countDown();
                        await(release);
                    }
                } finally {
                    depth.decrementAndGet();
                }
            }
        });
        mailbox.submit(commitRadius(12));
        Thread worker = new Thread(dispatcher.tasks.get(0), "config-mailbox-test");
        worker.start();
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));

        CommittedSnapshot latest = commitRadius(25);
        mailbox.submit(latest);
        release.countDown();
        worker.join(5000L);

        Assert.assertFalse(worker.isAlive());
        Assert.assertEquals(java.util.Arrays.asList(Integer.valueOf(12), Integer.valueOf(25)), published);
        Assert.assertEquals(1, maxDepth.get());
        Assert.assertEquals(latest.epoch, mailbox.appliedEpoch());
    }

    @Test
    public void directDispatcherReentrantSubmitNeverRecursesPublication() {
        final AtomicInteger depth = new AtomicInteger();
        final AtomicInteger maxDepth = new AtomicInteger();
        final List<Integer> published = new ArrayList<Integer>();
        final Mailbox[] holder = new Mailbox[1];
        holder[0] = new Mailbox("client", new DirectDispatcher(), new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                int currentDepth = depth.incrementAndGet();
                maxDepth.set(Math.max(maxDepth.get(), currentDepth));
                try {
                    published.add(Integer.valueOf(snapshot.chainRadius));
                    if (snapshot.chainRadius == 13) {
                        holder[0].submit(commitRadius(26));
                    }
                } finally {
                    depth.decrementAndGet();
                }
            }
        });

        holder[0].submit(commitRadius(13));

        Assert.assertEquals(java.util.Arrays.asList(Integer.valueOf(13), Integer.valueOf(26)), published);
        Assert.assertEquals(1, maxDepth.get());
    }

    @Test
    public void dispatcherFalseRetainsPendingAndLaterSubmitRecovers() {
        RecoveringDispatcher dispatcher = new RecoveringDispatcher(false);
        List<Integer> published = new ArrayList<Integer>();
        Mailbox mailbox = mailbox("client", dispatcher, published);
        mailbox.submit(commitRadius(14));

        CommittedSnapshot latest = commitRadius(28);
        mailbox.submit(latest);
        dispatcher.runQueued();

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(28)), published);
        Assert.assertEquals(latest.epoch, mailbox.appliedEpoch());
    }

    @Test
    public void dispatcherRuntimeExceptionRetainsPendingAndLaterSubmitRecovers() {
        RecoveringDispatcher dispatcher = new RecoveringDispatcher(true);
        List<Integer> published = new ArrayList<Integer>();
        Mailbox mailbox = mailbox("server", dispatcher, published);
        mailbox.submit(commitRadius(15));

        mailbox.submit(commitRadius(30));
        dispatcher.runQueued();

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(30)), published);
    }

    @Test
    public void publicationRuntimeExceptionDoesNotAdvanceAndNewPendingContinues() {
        final List<Integer> published = new ArrayList<Integer>();
        final Mailbox[] holder = new Mailbox[1];
        holder[0] = new Mailbox("client", new DirectDispatcher(), new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                if (snapshot.chainRadius == 16) {
                    holder[0].submit(commitRadius(32));
                    throw new IllegalStateException("forced publication failure");
                }
                published.add(Integer.valueOf(snapshot.chainRadius));
            }
        });
        CommittedSnapshot failed = commitRadius(16);

        holder[0].submit(failed);

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(32)), published);
        Assert.assertTrue(holder[0].appliedEpoch() > failed.epoch);
    }

    @Test
    public void publicationErrorReleasesOwnerAndPropagates() {
        final List<Integer> published = new ArrayList<Integer>();
        Mailbox mailbox = new Mailbox("client", new DirectDispatcher(), new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                if (snapshot.chainRadius == 17) {
                    throw new AssertionError("forced fatal publication");
                }
                published.add(Integer.valueOf(snapshot.chainRadius));
            }
        });
        try {
            mailbox.submit(commitRadius(17));
            Assert.fail("Error must propagate");
        } catch (AssertionError expected) {
            Assert.assertEquals("forced fatal publication", expected.getMessage());
        }

        CommittedSnapshot recovered = commitRadius(34);
        mailbox.submit(recovered);

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(34)), published);
        Assert.assertEquals(recovered.epoch, mailbox.appliedEpoch());
    }

    @Test
    public void clientAndServerReverseExecutionBothEndAtSameLatestSnapshot() {
        QueueDispatcher clientDispatcher = new QueueDispatcher();
        QueueDispatcher serverDispatcher = new QueueDispatcher();
        List<Integer> client = new ArrayList<Integer>();
        List<Integer> server = new ArrayList<Integer>();
        Mailbox clientMailbox = mailbox("client", clientDispatcher, client);
        Mailbox serverMailbox = mailbox("server", serverDispatcher, server);
        ConfigSnapshotDispatch.dispatch(commitRadius(18), clientMailbox, true, serverMailbox);
        CommittedSnapshot latest = commitRadius(36);
        ConfigSnapshotDispatch.dispatch(latest, clientMailbox, true, serverMailbox);

        serverDispatcher.runAt(0);
        clientDispatcher.runAt(0);

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(36)), client);
        Assert.assertEquals(Collections.singletonList(Integer.valueOf(36)), server);
        Assert.assertEquals(latest.epoch, clientMailbox.appliedEpoch());
        Assert.assertEquals(latest.epoch, serverMailbox.appliedEpoch());
    }

    @Test
    public void oneSideDispatcherFailureIsBestEffortAndRecoversOnLaterSubmit() {
        RecoveringDispatcher clientDispatcher = new RecoveringDispatcher(false);
        QueueDispatcher serverDispatcher = new QueueDispatcher();
        List<Integer> client = new ArrayList<Integer>();
        List<Integer> server = new ArrayList<Integer>();
        Mailbox clientMailbox = mailbox("client", clientDispatcher, client);
        Mailbox serverMailbox = mailbox("server", serverDispatcher, server);
        ConfigSnapshotDispatch.dispatch(commitRadius(19), clientMailbox, true, serverMailbox);
        CommittedSnapshot latest = commitRadius(38);
        ConfigSnapshotDispatch.dispatch(latest, clientMailbox, true, serverMailbox);

        clientDispatcher.runQueued();
        serverDispatcher.runAt(0);

        Assert.assertEquals(Collections.singletonList(Integer.valueOf(38)), client);
        Assert.assertEquals(Collections.singletonList(Integer.valueOf(38)), server);
    }

    private CommittedSnapshot commitRadius(int radius) {
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(radius));
        Assert.assertTrue(manager.save(draft).isSuccess());
        return ConfigBootstrap.captureCommittedSnapshot(manager);
    }

    private static Mailbox mailbox(
            String side,
            ConfigSnapshotDispatch.Dispatcher dispatcher,
            final List<Integer> published) {
        return new Mailbox(side, dispatcher, new ConfigSnapshotDispatch.Publication() {
            @Override
            public void publish(ValidatedSnapshot snapshot) {
                published.add(Integer.valueOf(snapshot.chainRadius));
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
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

    private static final class DirectDispatcher implements ConfigSnapshotDispatch.Dispatcher {
        @Override
        public boolean dispatch(Runnable task) {
            task.run();
            return true;
        }
    }

    private static final class RecoveringDispatcher implements ConfigSnapshotDispatch.Dispatcher {
        private final boolean throwFirst;
        private int calls;
        private Runnable queued;

        private RecoveringDispatcher(boolean throwFirst) {
            this.throwFirst = throwFirst;
        }

        @Override
        public boolean dispatch(Runnable task) {
            calls++;
            if (calls == 1) {
                if (throwFirst) {
                    throw new IllegalStateException("forced dispatcher failure");
                }
                return false;
            }
            queued = task;
            return true;
        }

        void runQueued() {
            Assert.assertNotNull(queued);
            queued.run();
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
