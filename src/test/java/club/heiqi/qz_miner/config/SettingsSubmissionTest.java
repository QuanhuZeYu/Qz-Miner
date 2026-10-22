package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import club.heiqi.qz_miner.client.settings.MinerSettingsAccess;
import club.heiqi.qz_miner.client.settings.MinerSettingsDraft;
import club.heiqi.qz_miner.client.settings.MinerSettingsSave;
import club.heiqi.uilib.ui.config.ConfigDocument;

/** 排队仅替代线程调度；保存经过真实原子事务和真实文件系统。 */
public class SettingsSubmissionTest {
    private File directory;
    private final QueueExecutor io = new QueueExecutor();
    private final QueueExecutor ui = new QueueExecutor();
    private final List<CommittedSnapshot> notifications = new ArrayList<CommittedSnapshot>();
    @Before public void setUp() throws Exception {
        ConfigTestSupport.reset();
        directory = Files.createTempDirectory("qz-settings-save-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
        ConfigBootstrap.subscribe(notifications::add);
    }
    @After public void tearDown() { ConfigTestSupport.reset(); delete(directory); }

    @Test public void staleDraftCannotOverwriteAnotherCommittedChange() throws Exception {
        MinerSettingsDraft draft = draft();
        draft.set(QzMinerConfigSchema.CHAIN_RADIUS, "24");
        ConfigBootstrap.commit(ConfigTestSupport.changes("general.greeting", "other-entry"));
        ConfigDocument latest = ConfigBootstrap.document();
        byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        List<MinerSettingsSave.Result> results = new ArrayList<MinerSettingsSave.Result>();
        saver(new AtomicBoolean(true)).save(draft, results::add);
        Assert.assertTrue(results.isEmpty());
        io.next(); ui.next(); io.next(); ui.next();
        Assert.assertFalse(results.get(0).success());
        Assert.assertTrue(draft.dirty());
        Assert.assertSame(latest, ConfigBootstrap.document());
        Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        Assert.assertEquals(1, notifications.size());
    }

    @Test public void closeOrConnectionReplacementDropsOnlyPageCompletion() throws Exception {
        for (boolean close : new boolean[] {true, false}) {
            AtomicBoolean current = new AtomicBoolean(true);
            MinerSettingsSave save = saver(current);
            MinerSettingsDraft draft = draft();
            draft.set(QzMinerConfigSchema.GREETING, close ? "closed" : "replaced");
            List<MinerSettingsSave.Result> results = new ArrayList<MinerSettingsSave.Result>();
            Assert.assertTrue(save.save(draft, results::add));
            Assert.assertEquals(1, io.jobs.size());
            io.next(); ui.next(); io.next();
            Assert.assertEquals(close ? "closed" : "replaced", ConfigBootstrap.document().get(QzMinerConfigSchema.GREETING));
            MinerSettingsDraft fresh = draft();
            fresh.set(QzMinerConfigSchema.GREETING, "new-page");
            if (close) save.close(); else current.set(false);
            ui.next();
            Assert.assertTrue(results.isEmpty());
            Assert.assertEquals("new-page", fresh.value(QzMinerConfigSchema.GREETING));
        }
    }

    @Test public void invalidCandidateNeverQueuesIoAndFailedWriteDoesNotPublish() throws Exception {
        MinerSettingsDraft invalid = draft();
        invalid.set(QzMinerConfigSchema.HUD_SCALE, "NaN");
        Assert.assertFalse(saver(new AtomicBoolean(true)).save(invalid, ignored -> { }));
        Assert.assertTrue(io.jobs.isEmpty());
        MinerSettingsDraft draft = draft();
        draft.set(QzMinerConfigSchema.HUD_SCALE, "1.5");
        ConfigDocument before = ConfigBootstrap.document();
        File yaml = ConfigBootstrap.yamlFile();
        Files.move(yaml.toPath(), new File(directory, "original.yaml").toPath());
        Files.createDirectory(yaml.toPath());
        Files.write(new File(yaml, "occupied").toPath(), new byte[] {1});
        List<MinerSettingsSave.Result> results = new ArrayList<MinerSettingsSave.Result>();
        saver(new AtomicBoolean(true)).save(draft, results::add);
        io.next(); ui.next(); io.next(); ui.next();
        Assert.assertFalse(results.get(0).success());
        Assert.assertSame(before, ConfigBootstrap.document());
        Assert.assertTrue(notifications.isEmpty());
        Assert.assertTrue(draft.dirty());
    }

    @Test public void revokedWhileQueuedIsRejectedOnUiAdmissionWithoutTouchingAuthorityOrDisk() throws Exception {
        AtomicBoolean eligible = new AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        MinerSettingsSave save = new MinerSettingsSave(io, ui, () -> true, () -> {
            checks.incrementAndGet(); return eligible.get();
        });
        MinerSettingsDraft draft = draft();
        draft.set(QzMinerConfigSchema.CHAIN_RADIUS, "24");
        draft.set(QzMinerConfigSchema.AUTO_TOOL_SWAP_ENABLED, false);
        ConfigDocument before = ConfigBootstrap.document();
        byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        List<MinerSettingsSave.Result> results = new ArrayList<MinerSettingsSave.Result>();
        Assert.assertTrue(save.save(draft, results::add));
        Assert.assertEquals(1, checks.get());
        eligible.set(false);
        io.next();
        Assert.assertEquals("worker 不读取资格", 1, checks.get());
        ui.next();
        Assert.assertEquals(2, checks.get());
        Assert.assertFalse(results.get(0).success());
        Assert.assertTrue(draft.dirty());
        Assert.assertTrue(io.jobs.isEmpty());
        Assert.assertSame(before, ConfigBootstrap.document());
        Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        Assert.assertTrue(notifications.isEmpty());
    }

    private MinerSettingsDraft draft() { return new MinerSettingsDraft(ConfigBootstrap.document(), MinerSettingsAccess.MENU); }
    private MinerSettingsSave saver(AtomicBoolean current) {
        return new MinerSettingsSave(io, ui, current::get);
    }
    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> jobs = new ArrayDeque<Runnable>();
        public void execute(Runnable task) { jobs.add(task); }
        void next() { jobs.remove().run(); }
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
