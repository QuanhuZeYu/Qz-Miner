package club.heiqi.qz_miner.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 模式邻项、阶段状态与确认明细分别失效；稳定 tick 只读取固定标量事实。 */
public final class MinerHudModel {
    private final Function<String, String> text;
    /** 既有读取入口；标题只从 selection 派生，不再维护另一份模式事实。 */
    public final ReadableSignal<String> heading;
    public final Signal<String> status;
    public final Signal<Boolean> expanded;
    public final Signal<List<Row>> rows;
    public final Signal<MinerHudSelection> selection;
    public final Signal<Boolean> armed, sustained, warning;
    public final Signal<Object> activity, navigation;
    public final Signal<Float> bearing;
    private final ConfirmationFacts confirmation = new ConfirmationFacts();
    private ChainPreviewPresentationHeader header;
    private boolean key;
    private String locale;
    private long resources, capabilities;
    private Object selectionIntent;
    private boolean contextSeen;

    public MinerHudModel(ReactiveDomain domain, Function<String, String> text) {
        this.text = text;
        status = domain.signal(text.apply("hud.qz_miner.phase.IDLE"));
        expanded = domain.signal(false); rows = domain.signal(Collections.<Row>emptyList());
        selection = domain.signal(new MinerHudSelection(ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, null, text, false));
        heading = domain.computed(() -> {
            MinerHudSelection selected = selection.get();
            return selected.title + (selected.subMode == null ? "" : " · " + selected.subtitle);
        });
        armed = domain.signal(false); sustained = domain.signal(false); warning = domain.signal(false);
        activity = domain.signal(null); navigation = domain.signal(null); bearing = domain.signal(0f);
        projectStatus(); projectDetails();
    }

    public void header(ChainPreviewPresentationHeader next) {
        if (header == next) return;
        boolean changed = header != null && next != null && (header.getPhase() != next.getPhase()
                || header.isRemoteRequestPending() != next.isRemoteRequestPending()
                || header.getCancelReason() != next.getCancelReason());
        if (changed || header == null && next != null && next.getPhase() != ChainPhase.IDLE) activity.set(new Object());
        header = next;
        projectStatus(); projectDetails();
    }

    public void context(ChainClientState state, boolean known, String language) { context(state, known, language, 0L); }

    /** 资源与能力代际各自失效；保留宿主既有重载，读取能力代际不触发兼容探测。 */
    public void context(ChainClientState state, boolean known, String language, long resourceRevision) {
        if (state == null) return;
        long capabilityRevision = ChainModeRegistry.availabilityRevision();
        ChainMode nextMode = state.getSelectedMode();
        ChainSubMode nextSubMode = state.getSelectedSubMode();
        MinerHudSelection current = selection.get();
        boolean choiceChanged = current.mode != nextMode || current.subMode != nextSubMode;
        boolean nextKey = state.isChainKeyPressed();
        boolean keyChanged = key != nextKey;
        boolean knownChanged = confirmation.confirmed != known;
        boolean confirmationChanged = confirmation.update(state, known);
        boolean labelsChanged = !Objects.equals(locale, language) || resources != resourceRevision;
        boolean capabilitiesChanged = capabilities != capabilityRevision;
        Object nextIntent = state.getSelectionIntent();
        int direction = selectionIntent != nextIntent ? state.getSelectionDirection() : 0;
        // 即使本次输入未改变选择也消费意图，资料失效不得重放旧滚轮方向。
        selectionIntent = nextIntent;
        if (contextSeen && !choiceChanged && !keyChanged && !confirmationChanged && !labelsChanged && !capabilitiesChanged) return;

        if (!contextSeen || choiceChanged) {
            projectChoice(nextMode, nextSubMode, contextSeen ? current : null, direction);
        } else if (labelsChanged || capabilitiesChanged) {
            // 能力与文案刷新不表达导航，不唤醒 HUD，也不推进刻度。
            selection.set(current.refresh(text));
        }
        if ((contextSeen && choiceChanged) || keyChanged) {
            Object event = new Object(); activity.set(event); navigation.set(event);
        }
        key = nextKey;
        locale = language; resources = resourceRevision; capabilities = capabilityRevision;
        if (!contextSeen || keyChanged || knownChanged || labelsChanged) projectStatus();
        if (!contextSeen || confirmationChanged || labelsChanged) projectDetails();
        contextSeen = true;
    }

    private void projectChoice(ChainMode mode, ChainSubMode subMode, MinerHudSelection previous, int direction) {
        MinerHudSelection selected = new MinerHudSelection(mode, subMode, previous, text, true, direction);
        selection.set(selected);
        if (selected.horizontal != 0 || selected.vertical != 0)
            bearing.set(bearing.get() + selected.horizontal * 30f + selected.vertical * 7.5f);
    }

    private void projectStatus() {
        ChainPhase phase = header == null ? ChainPhase.IDLE : header.getPhase();
        boolean pending = header != null && header.isRemoteRequestPending();
        boolean failed = header != null && (header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_TIMEOUT
                || header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE);
        armed.set(key); sustained.set(key || phase != ChainPhase.IDLE || pending); warning.set(failed);
        String currentStatus = text.apply(confirmation.confirmed ? "hud.qz_miner.phase."
                + (key && phase == ChainPhase.IDLE ? ChainPhase.ARMED : phase).name() : "hud.qz_miner.sync.pending");
        if (failed) currentStatus += " · " + text.apply(header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_TIMEOUT
                ? "hud.qz_miner.preview.remote.timeout" : "hud.qz_miner.preview.remote.unavailable");
        else if (pending) currentStatus += " · " + text.apply("hud.qz_miner.preview.remote.pending");
        status.set(currentStatus);
        expanded.set(key || phase != ChainPhase.IDLE || header != null && (pending
                || header.getCancelReason() != ChainPreviewState.CancelReason.NONE));
    }

    private void projectDetails() {
        // 明细是事实投影；是否展开由呈现决定，空闲编辑也可完整预览。
        List<Row> next = new ArrayList<Row>();
        String pendingLabel = text.apply("hud.qz_miner.sync.pending");
        add(next, "radius", "hud.qz_miner.short.radius", confirmation.requestedRadius + " / "
                + (confirmation.confirmed ? confirmation.acceptedRadius : pendingLabel));
        add(next, "blocks", "hud.qz_miner.short.blocks", confirmation.requestedBlocks + " / "
                + (confirmation.confirmed ? confirmation.acceptedBlocks : pendingLabel));
        add(next, "groups", "hud.qz_miner.short.groups", text.apply(confirmation.groupsAccepted
                ? "hud.qz_miner.sync.confirmed" : "hud.qz_miner.sync.pending"));
        if (confirmation.confirmed && confirmation.serverMatched > 0)
            add(next, "server", "hud.qz_miner.server_recent.label", String.valueOf(confirmation.serverMatched));
        if (header != null) {
            if (header.isPreviewActive()) add(next, "preview", "hud.qz_miner.short.preview", header.getMatchedCount() + " · "
                    + text.apply(header.isPreviewCompleted() ? "hud.qz_miner.preview.completed" : "hud.qz_miner.preview.calculating"));
            if (header.isRemoteRequestPending()) add(next, "remote", "hud.qz_miner.short.remote", text.apply("hud.qz_miner.preview.remote.pending"));
            if (header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_TIMEOUT)
                add(next, "remote", "hud.qz_miner.short.remote", text.apply("hud.qz_miner.preview.remote.timeout"));
            if (header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE)
                add(next, "remote", "hud.qz_miner.short.remote", text.apply("hud.qz_miner.preview.remote.unavailable"));
            if (header.isTruncationSignalEnabled() && header.getTruncationReason() != ChainPreviewState.TruncationReason.NONE)
                add(next, "truncated", "hud.qz_miner.preview.truncated.label", text.apply("hud.qz_miner.preview.truncated.reason."
                        + header.getTruncationReason().name().toLowerCase(java.util.Locale.ROOT)) + " · " + header.getTruncatedCount());
            if (header.isExecutionProgressEnabled() && header.getMatchedCount() > 0)
                add(next, "estimate", "hud.qz_miner.preview.estimate", header.getExecutedCount() + " / " + header.getMatchedCount());
            if (header.isBackendDiagnosticsEnabled() && !header.getActiveBackendId().isEmpty())
                add(next, "backend", "hud.qz_miner.preview.backend.label", header.getActiveBackendId()
                        + (header.getBackendFallbackReason().isEmpty() ? "" : " · " + header.getBackendFallbackReason()));
        }
        rows.set(Collections.unmodifiableList(next));
    }

    private void add(List<Row> next, String id, String label, String value) { next.add(new Row(id, text.apply(label), value)); }

    /** 服务端确认与请求参数的独立事实源，不因模式邻项失效而重建明细。 */
    private static final class ConfirmationFacts {
        boolean confirmed, groupsAccepted;
        int requestedRadius, acceptedRadius, requestedBlocks, acceptedBlocks, serverMatched;

        boolean update(ChainClientState state, boolean known) {
            boolean nextGroups = state.isObjectGroupSyncAccepted();
            int rr = state.getRequestedChainRadius(), ar = state.getServerChainRadius();
            int rb = state.getRequestedChainMaxBlocks(), ab = state.getServerChainMaxBlocks();
            int matched = state.getServerMatchedTargetCount();
            if (confirmed == known && groupsAccepted == nextGroups && requestedRadius == rr && acceptedRadius == ar
                    && requestedBlocks == rb && acceptedBlocks == ab && serverMatched == matched) return false;
            confirmed = known; groupsAccepted = nextGroups;
            requestedRadius = rr; acceptedRadius = ar; requestedBlocks = rb; acceptedBlocks = ab; serverMatched = matched;
            return true;
        }
    }

    public SceneNode content(ScenePageContext context) { return content(context, false); }
    public SceneNode content(ScenePageContext context, boolean editing) {
        return new MinerHudAppearance(context).content(this, editing);
    }

    public static final class Row {
        public final String id;
        public final String label;
        public final String value;
        Row(String id, String label, String value) { this.id = id; this.label = label; this.value = value; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Row)) return false;
            Row row = (Row) other;
            return id.equals(row.id) && label.equals(row.label) && value.equals(row.value);
        }
        @Override public int hashCode() { return Objects.hash(id, label, value); }
    }
}
