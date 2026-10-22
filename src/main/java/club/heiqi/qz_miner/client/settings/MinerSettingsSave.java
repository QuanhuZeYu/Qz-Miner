package club.heiqi.qz_miner.client.settings;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;

/** 队首回所属 UI 线程确认准入；后台只执行已准入候选的原事务，迟到完成只属于原页面。 */
public final class MinerSettingsSave implements AutoCloseable {
    private final Executor io;
    private final Executor ui;
    private final BooleanSupplier lifecycle;
    private final BooleanSupplier admission;
    private volatile boolean closed;
    private boolean saving;

    public MinerSettingsSave(Executor io, Executor ui, BooleanSupplier lifecycle) {
        this(io, ui, lifecycle, lifecycle);
    }
    public MinerSettingsSave(Executor io, Executor ui, BooleanSupplier lifecycle, BooleanSupplier admission) {
        this.io = Objects.requireNonNull(io, "io");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    public boolean save(MinerSettingsDraft draft, Consumer<Result> completion) {
        if (closed || saving || !lifecycle.getAsBoolean()) return false;
        if (!admission.getAsBoolean()) {
            completion.accept(new Result(null, "页面连接或管理资格已变更，请重新打开设置"));
            return false;
        }
        MinerSettingsDraft.Validation validated = draft.validate();
        if (!validated.valid()) {
            completion.accept(new Result(null, validated.errors.toString()));
            return false;
        }
        saving = true;
        try {
            // 排队等待结束后回所属线程重新读取真实资格；worker 不读取 Minecraft/权限状态。
            io.execute(() -> ui.execute(() -> admit(draft, validated, completion)));
        } catch (RuntimeException failure) {
            finish(new Result(null, failure.getMessage()), completion);
            return false;
        }
        return true;
    }

    private void admit(MinerSettingsDraft draft, MinerSettingsDraft.Validation validated, Consumer<Result> completion) {
        if (closed || !lifecycle.getAsBoolean()) return;
        if (!admission.getAsBoolean()) {
            finish(new Result(null, "页面连接或管理资格已变更，请重新打开设置"), completion);
            return;
        }
        try {
            // 准入是资格冻结点；此后原 CAS/原子落盘不增加回滚、重试或强行取消。
            io.execute(() -> {
                Result result;
                try { result = new Result(ConfigBootstrap.commit(draft.baseline(), validated.updates), null); }
                catch (Exception failure) { result = new Result(null, failure.getMessage()); }
                final Result completed = result;
                ui.execute(() -> finish(completed, completion));
            });
        } catch (RuntimeException failure) { finish(new Result(null, failure.getMessage()), completion); }
    }

    /** 只由所属 UI executor 调用；资格变化后的同页仍需解除 busy，跨生命周期的旧页不接收完成。 */
    private void finish(Result result, Consumer<Result> completion) {
        if (closed || !lifecycle.getAsBoolean()) return;
        saving = false;
        completion.accept(result);
    }
    @Override public void close() { closed = true; }

    public static final class Result {
        public final CommittedSnapshot committed;
        public final String error;
        Result(CommittedSnapshot committed, String error) { this.committed = committed; this.error = error; }
        public boolean success() { return committed != null; }
    }
}
