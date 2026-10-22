package club.heiqi.qz_miner.client;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch.Mailbox;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/** 显式提交订阅与 mailbox 生命周期；测试侧观察队列，不向生产增加注入构造器。 */
public class ClientConfigChangeListenerTest {
    private File directory;
    private final List<ClientConfigChangeListener> listeners = new ArrayList<ClientConfigChangeListener>();

    @Before
    public void setUp() throws Exception {
        resetSubscription();
        ConfigTestSupport.reset();
        directory = Files.createTempDirectory("qz-listener-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
    }
    @After
    public void tearDown() throws Exception {
        for (ClientConfigChangeListener listener : listeners) listener.unregister();
        resetSubscription();
        ConfigTestSupport.reset();
        delete(directory);
    }

    @Test
    public void initialRegisterDoesNotSeedDuplicatePublicationAndRepeatIsIdempotent() throws Exception {
        Harness harness = harness();
        harness.listener.register();
        harness.listener.register();
        Assert.assertTrue(harness.client.tasks.isEmpty());
        Assert.assertTrue(harness.server.tasks.isEmpty());
    }

    @Test
    public void replacementClosesOldQueueAndSeedsCurrentWithoutExtraRevision() throws Exception {
        Harness old = harness();
        Harness replacement = harness();
        old.listener.register();
        CommittedSnapshot saved = saveRadius(23);
        Assert.assertEquals(1, old.client.tasks.size());
        replacement.listener.register();
        Assert.assertSame(saved, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(1, replacement.client.tasks.size());
        replacement.client.runAt(0);
        old.client.runAt(0);
        Assert.assertEquals(0, old.published.get());
        Assert.assertEquals(1, replacement.published.get());
        Assert.assertSame(saved, replacement.last);
    }

    @Test
    public void reloadNotifiesAlreadyCommittedSnapshotAndWaitsForClientQueue() throws Exception {
        Harness harness = harness();
        harness.listener.register();
        List<CommittedSnapshot> observed = new ArrayList<CommittedSnapshot>();
        ConfigBootstrap.subscribe(committed -> {
            Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
            observed.add(committed);
        });
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "client:\n  clientEnablePreviewRender: false\n".getBytes(StandardCharsets.UTF_8));
        CommittedSnapshot reloaded = ConfigBootstrap.reload();
        Assert.assertEquals(java.util.Collections.singletonList(reloaded), observed);
        Assert.assertEquals(Boolean.FALSE, ConfigBootstrap.document().get(QzMinerConfigSchema.CLIENT_ENABLE_PREVIEW_RENDER));
        Assert.assertTrue(Config.clientEnablePreviewRender);
        Assert.assertEquals(1, harness.client.tasks.size());
        harness.client.runAt(0);
        Assert.assertSame(reloaded, harness.last);
        Assert.assertFalse(Config.clientEnablePreviewRender);
    }

    @Test
    public void replacementSeesSaveWithoutListenerFromTheAlreadyCurrentAuthority() throws Exception {
        Harness old = harness();
        old.listener.register();
        ConfigBootstrap.unsubscribe(old.listener);
        CommittedSnapshot saved = saveRadius(77);
        Assert.assertSame(saved, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertTrue(old.client.tasks.isEmpty());
        Harness replacement = harness();
        replacement.listener.register();
        replacement.client.runAt(0);
        Assert.assertSame(saved, replacement.last);
        Assert.assertEquals(77, replacement.last.snapshot.chainRadius);
    }

    @Test
    public void unregisterAndReplacementMakeOldCallbacksAndQueuedWorkNoOps() throws Exception {
        Harness old = harness();
        Harness replacement = harness();
        old.listener.register();
        CommittedSnapshot saved = saveRadius(24);
        replacement.listener.register();
        replacement.listener.unregister();
        old.listener.onCommitted(saved);
        replacement.listener.onCommitted(saved);
        replacement.client.runAt(0);
        old.client.runAt(0);
        Assert.assertEquals(0, old.published.get());
        Assert.assertEquals(0, replacement.published.get());
    }

    @Test
    public void staleAndNullCallbacksDoNotPublishOrCreateEpochs() throws Exception {
        Harness harness = harness();
        harness.listener.register();
        CommittedSnapshot old = ConfigBootstrap.currentCommittedSnapshot();
        CommittedSnapshot latest = saveRadius(25);
        harness.listener.onCommitted(old);
        harness.listener.onCommitted(null);
        Assert.assertSame(latest, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(1, harness.client.tasks.size());
        harness.client.runAt(0);
        Assert.assertSame(latest, harness.last);
    }

    @Test
    public void oldBootstrapCallbackCannotDispatchIntoNewAuthority() throws Exception {
        Harness old = harness();
        old.listener.register();
        CommittedSnapshot previous = saveRadius(26);
        ConfigTestSupport.reset();
        ConfigBootstrap.bootstrap(new File(directory, "replacement"), null);
        old.listener.onCommitted(previous);
        old.client.runAt(0);
        Assert.assertEquals(0, old.published.get());
        Harness replacement = harness();
        replacement.listener.register();
        replacement.client.runAt(0);
        Assert.assertSame(ConfigBootstrap.currentCommittedSnapshot(), replacement.last);
    }

    private Harness harness() throws Exception {
        Harness harness = new Harness();
        listeners.add(harness.listener);
        return harness;
    }
    private static CommittedSnapshot saveRadius(int radius) throws Exception {
        return ConfigBootstrap.commit(ConfigTestSupport.changes("general.chainRadius", Double.valueOf(radius)));
    }
    private static void resetSubscription() throws Exception {
        Field field = ClientConfigChangeListener.class.getDeclaredField("subscribedListener");
        field.setAccessible(true);
        ClientConfigChangeListener current = (ClientConfigChangeListener) field.get(null);
        if (current != null) current.unregister();
    }
    private static final class Harness {
        final Queue client = new Queue();
        final Queue server = new Queue();
        final AtomicInteger published = new AtomicInteger();
        final ClientConfigChangeListener listener = new ClientConfigChangeListener();
        CommittedSnapshot last;

        Harness() throws Exception {
            replaceMailbox("clientMailbox", new Mailbox("test-client", client,
                    (ConfigSnapshotDispatch.CommittedPublication) committed -> {
                        last = committed;
                        ConfigValueBridge.applyClientFromSnapshot(committed.snapshot);
                        published.incrementAndGet();
                    }));
            replaceMailbox("serverMailbox", new Mailbox("test-server", server,
                    (ConfigSnapshotDispatch.CommittedPublication) committed -> { }));
        }
        private void replaceMailbox(String name, Mailbox mailbox) throws Exception {
            Field field = ClientConfigChangeListener.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(listener, mailbox);
        }
    }
    private static final class Queue implements ConfigSnapshotDispatch.Dispatcher {
        final List<Runnable> tasks = new ArrayList<Runnable>();
        public boolean dispatch(Runnable task) {
            tasks.add(task);
            return true;
        }
        void runAt(int index) { tasks.get(index).run(); }
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
