package club.heiqi.qz_miner.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** 只消费固定标量事实；文字和行列表只在 header、模式、语言或确认事实变化时派生。 */
public final class MinerHudModel {
    private final Function<String, String> text;
    public final Signal<String> heading;
    public final Signal<String> status;
    public final Signal<Boolean> expanded;
    public final Signal<List<Row>> rows;
    public final Signal<MinerHudSelection> selection;
    public final Signal<Boolean> armed, sustained, warning;
    public final Signal<Object> activity, navigation;
    public final Signal<Float> bearing;
    private ChainPreviewPresentationHeader header;
    private ChainMode mode;
    private ChainSubMode subMode;
    private boolean key;
    private boolean confirmed;
    private boolean groupsAccepted;
    private int requestedRadius, acceptedRadius, requestedBlocks, acceptedBlocks, serverMatched;
    private String locale;
    private long resources;
    private Object selectionIntent;
    private boolean contextSeen;

    public MinerHudModel(ReactiveDomain domain, Function<String, String> text) {
        this.text = text;
        heading = domain.signal(text.apply("settings.qz_miner.hud.preview_title"));
        status = domain.signal(text.apply("hud.qz_miner.phase.IDLE"));
        expanded = domain.signal(false); rows = domain.signal(Collections.<Row>emptyList());
        selection = domain.signal(new MinerHudSelection(ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, null, text, false));
        armed = domain.signal(false); sustained = domain.signal(false); warning = domain.signal(false);
        activity = domain.signal(null); navigation = domain.signal(null); bearing = domain.signal(0f);
        mode = ChainMode.CHAIN; subMode = ChainSubMode.CHAIN_BASE; rebuild();
    }

    public void header(ChainPreviewPresentationHeader next) {
        if (header == next) return;
        boolean changed = header != null && next != null && (header.getPhase() != next.getPhase()
                || header.isRemoteRequestPending() != next.isRemoteRequestPending()
                || header.getCancelReason() != next.getCancelReason());
        if (changed || header == null && next != null && next.getPhase() != ChainPhase.IDLE) activity.set(new Object());
        header = next; rebuild();
    }

    public void context(ChainClientState state, boolean known, String language) { context(state, known, language, 0L); }

    /** 宿主资源代际含同语言重载，稳定 tick 只比较标量，不重译所有文案。 */
    public void context(ChainClientState state, boolean known, String language, long resourceRevision) {
        if (state == null) return;
        ChainMode nextMode = state.getSelectedMode();
        ChainSubMode nextSubMode = state.getSelectedSubMode();
        boolean nextKey = state.isChainKeyPressed();
        boolean nextGroups = state.isObjectGroupSyncAccepted();
        int rr = state.getRequestedChainRadius(), ar = state.getServerChainRadius();
        int rb = state.getRequestedChainMaxBlocks(), ab = state.getServerChainMaxBlocks();
        int matched = state.getServerMatchedTargetCount();
        if (contextSeen && mode == nextMode && subMode == nextSubMode && key == nextKey && confirmed == known
                && groupsAccepted == nextGroups && requestedRadius == rr && acceptedRadius == ar
                && requestedBlocks == rb && acceptedBlocks == ab && serverMatched == matched
                && Objects.equals(locale, language) && resources == resourceRevision) return;
        boolean modeChanged = contextSeen && (mode != nextMode || subMode != nextSubMode);
        boolean keyChanged = key != nextKey;
        if (!contextSeen || mode != nextMode || subMode != nextSubMode || !Objects.equals(locale, language) || resources != resourceRevision) {
            MinerHudSelection previous = contextSeen ? selection.get() : null;
            MinerHudSelection selected = new MinerHudSelection(nextMode, nextSubMode, previous, text, true,
                    selectionIntent != state.getSelectionIntent() ? state.getSelectionDirection() : 0);
            selection.set(selected);
            bearing.set(bearing.get() + selected.horizontal * 30f + selected.vertical * 7.5f);
        }
        if (modeChanged || keyChanged) {
            Object event = new Object(); activity.set(event); navigation.set(event);
        }
        mode = nextMode; subMode = nextSubMode; key = nextKey; confirmed = known; groupsAccepted = nextGroups;
        requestedRadius = rr; acceptedRadius = ar; requestedBlocks = rb; acceptedBlocks = ab; serverMatched = matched;
        locale = language; resources = resourceRevision; selectionIntent = state.getSelectionIntent(); contextSeen = true; rebuild();
    }

    private void rebuild() {
        if (mode == null) return;
        ChainPhase phase = header == null ? ChainPhase.IDLE : header.getPhase();
        boolean pending = header != null && header.isRemoteRequestPending();
        boolean failed = header != null && (header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_TIMEOUT
                || header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE);
        armed.set(key); sustained.set(key || phase != ChainPhase.IDLE || pending); warning.set(failed);
        String currentStatus = text.apply(confirmed ? "hud.qz_miner.phase."
                + (key && phase == ChainPhase.IDLE ? ChainPhase.ARMED : phase).name() : "hud.qz_miner.sync.pending");
        if (failed) currentStatus += " · " + text.apply(header.getCancelReason() == ChainPreviewState.CancelReason.REMOTE_TIMEOUT
                ? "hud.qz_miner.preview.remote.timeout" : "hud.qz_miner.preview.remote.unavailable");
        else if (pending) currentStatus += " · " + text.apply("hud.qz_miner.preview.remote.pending");
        String currentHeading = text.apply(mode.getDisplayNameKey())
                + (subMode == null ? "" : " · " + text.apply(subMode.getDisplayNameKey()));
        heading.set(currentHeading); status.set(currentStatus);
        boolean showDetails = key || phase != ChainPhase.IDLE || header != null && (header.isRemoteRequestPending()
                || header.getCancelReason() != ChainPreviewState.CancelReason.NONE);
        expanded.set(showDetails);
        List<Row> next = new ArrayList<Row>();
        {
            // 明细是事实投影；是否展开由呈现决定，空闲编辑也可完整预览。
            String pendingLabel = text.apply("hud.qz_miner.sync.pending");
            add(next, "radius", "hud.qz_miner.short.radius", requestedRadius + " / " + (confirmed ? acceptedRadius : pendingLabel));
            add(next, "blocks", "hud.qz_miner.short.blocks", requestedBlocks + " / " + (confirmed ? acceptedBlocks : pendingLabel));
            add(next, "groups", "hud.qz_miner.short.groups", text.apply(groupsAccepted ? "hud.qz_miner.sync.confirmed" : "hud.qz_miner.sync.pending"));
            if (confirmed && serverMatched > 0) add(next, "server", "hud.qz_miner.server_recent.label", String.valueOf(serverMatched));
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
        }
        rows.set(Collections.unmodifiableList(next));
    }

    private void add(List<Row> next, String id, String label, String value) { next.add(new Row(id, text.apply(label), value)); }

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
