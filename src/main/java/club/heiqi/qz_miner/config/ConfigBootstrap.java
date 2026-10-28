package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

/** YAML 文档维护业务权威，派生快照作为所属主线程的发布载荷。 */
public final class ConfigBootstrap {
    public static final String YAML_FILE_NAME = "qz_miner.yaml";
    private static final CopyOnWriteArrayList<CommitListener> LISTENERS = new CopyOnWriteArrayList<CommitListener>();
    private static volatile State state;
    private static long commitEpoch;

    private ConfigBootstrap() { }

    public static ConfigDocument document() {
        State current = state;
        return current == null ? null : current.document;
    }

    public static File yamlFile() {
        State current = state;
        return current == null ? null : current.yaml;
    }

    public static ValidatedSnapshot currentValidatedSnapshot() {
        return currentCommittedSnapshot().snapshot;
    }

    public static CommittedSnapshot currentCommittedSnapshot() {
        return requireState().committed;
    }

    public static boolean isCurrent(CommittedSnapshot committed) {
        State current = state;
        return current != null && committed != null && current.committed == committed;
    }

    /** 启动迁移全部完成后才发布权威与运行字段；相同目录重复调用幂等。 */
    public static synchronized ConfigDocument bootstrap(File configDir, File legacyCfg) {
        Objects.requireNonNull(configDir, "configDir");
        File yaml = new File(configDir, YAML_FILE_NAME);
        if (state != null) {
            try {
                if (state.yaml.getCanonicalFile().equals(yaml.getCanonicalFile())) return state.document;
            } catch (IOException failure) {
                throw new IllegalStateException("无法解析配置路径", failure);
            }
            throw new IllegalStateException("ConfigBootstrap already initialized for " + state.yaml);
        }
        long epoch = nextEpoch();
        try {
            Files.createDirectories(configDir.toPath());
            ConfigDocument loaded = ConfigStartupMigration.load(yaml, legacyCfg);
            ValidatedSnapshot snapshot = ConfigSemanticValidator.requireValid(loaded);
            State initialized = new State(yaml, loaded, new CommittedSnapshot(epoch, snapshot));
            ConfigValueBridge.applyAll(snapshot);
            commitEpoch = epoch;
            state = initialized;
            return loaded;
        } catch (IOException failure) {
            throw new IllegalStateException("配置启动事务失败: " + yaml, failure);
        }
    }

    /** 整批组候选 → 全量业务校验 → 内存编码 → 原子替换 → 更换权威 → 成功通知。 */
    public static CommittedSnapshot commit(Map<ConfigKey<?>, ?> updates) throws IOException {
        return commit(null, updates);
    }

    /** 页面草稿用文档身份作基线；比较与原事务共锁，旧草稿必须显式重新载入。 */
    public static CommittedSnapshot commit(ConfigDocument expectedBaseline, Map<ConfigKey<?>, ?> updates)
            throws IOException {
        CommittedSnapshot committed;
        synchronized (ConfigBootstrap.class) {
            State previous = requireState();
            if (expectedBaseline != null && previous.document != expectedBaseline) {
                throw new IllegalStateException("配置已由其他入口提交，请重新载入后再保存草稿");
            }
            ConfigDocument candidate = previous.document.withAll(updates);
            ValidatedSnapshot snapshot = ConfigSemanticValidator.requireValid(candidate);
            long epoch = nextEpoch();
            committed = new CommittedSnapshot(epoch, snapshot);
            State replacement = new State(previous.yaml, candidate, committed);
            ConfigStorage.write(previous.yaml.toPath(), candidate);
            commitEpoch = epoch;
            state = replacement;
        }
        notifyCommitted(committed);
        return committed;
    }

    /** 完整 read 与业务校验成功后更换权威，并发布同一提交令牌。 */
    public static CommittedSnapshot reload() throws IOException {
        CommittedSnapshot committed;
        synchronized (ConfigBootstrap.class) {
            State previous = requireState();
            ConfigDocument loaded = ConfigDocument.read(QzMinerConfigSchema.SCHEMA, previous.yaml.toPath());
            ValidatedSnapshot snapshot = ConfigSemanticValidator.requireValid(loaded);
            long epoch = nextEpoch();
            committed = new CommittedSnapshot(epoch, snapshot);
            State replacement = new State(previous.yaml, loaded, committed);
            commitEpoch = epoch;
            state = replacement;
        }
        notifyCommitted(committed);
        return committed;
    }

    /** 先订阅再返回 current，替换订阅者用同一已提交令牌补种。 */
    public static CommittedSnapshot subscribe(CommitListener listener) {
        requireState();
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
        return currentCommittedSnapshot();
    }

    public static void unsubscribe(CommitListener listener) {
        LISTENERS.remove(listener);
    }

    public static void reapplyGeneralOnServerStarting() {
        State current = state;
        if (current != null) ConfigValueBridge.applyGeneralFromSnapshot(current.committed.snapshot);
    }

    private static void notifyCommitted(CommittedSnapshot committed) {
        // 锁外通知隔离订阅生命周期与文件事务；mailbox 按 current identity 筛选载荷。
        for (CommitListener listener : LISTENERS) {
            try {
                listener.onCommitted(committed);
            } catch (RuntimeException failure) {
                // 提交已完成，观察者故障单独记录，继续通知其余观察者。
                MyMod.LOG.error("Config committed notification failed", failure);
            }
        }
    }

    private static long nextEpoch() {
        if (commitEpoch == Long.MAX_VALUE) throw new ConfigAuthorityInvariantError("Config commit epoch overflow");
        return commitEpoch + 1L;
    }

    private static State requireState() {
        State current = state;
        if (current == null) throw new IllegalStateException("currentValidatedSnapshot is not initialized");
        return current;
    }

    public interface CommitListener {
        void onCommitted(CommittedSnapshot committed);
    }

    private static final class State {
        final File yaml;
        final ConfigDocument document;
        final CommittedSnapshot committed;

        State(File yaml, ConfigDocument document, CommittedSnapshot committed) {
            this.yaml = yaml;
            this.document = document;
            this.committed = committed;
        }
    }
}
