package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.hud.api.HudLine;
import club.heiqi.uilib.ui.hud.api.HudSnapshot;
import club.heiqi.uilib.ui.hud.api.HudSpan;
import club.heiqi.uilib.ui.hud.api.HudTone;

/** 回归紧凑 HUD 的显示门、原信息行和稳定行标识。 */
public class QzMinerHudSnapshotProviderTest {

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @Test
    public void visibilityFollowsKeyAndActiveExecutionPhases() {
        Fixture fixture = new Fixture();
        assertVisible(fixture, ChainPhase.IDLE, false, false);
        assertVisible(fixture, ChainPhase.ARMED, false, false);
        assertVisible(fixture, ChainPhase.PLANNING, false, true);
        assertVisible(fixture, ChainPhase.RUNNING, false, true);
        assertVisible(fixture, ChainPhase.FINISHING, false, true);
        assertVisible(fixture, ChainPhase.IDLE, true, true);
        assertVisible(fixture, ChainPhase.ARMED, true, true);
    }

    @Test
    public void baseLinesKeepLegacyInformationAndOrder() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setRequestedChainRadius(7);
        fixture.state.setServerChainRadius(5);
        fixture.state.setRequestedChainMaxBlocks(99);
        fixture.state.setServerChainMaxBlocks(80);
        fixture.state.setServerMatchedTargetCount(12);

        Assert.assertEquals(Arrays.asList("status", "mode", "sub-mode", "chain-config",
                "server-matched", "object-group-sync"), ids(fixture.provider.snapshot()));
    }

    @Test
    public void previewPrecedesAreaAndBothAreConditional() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));
        fixture.preview.addPreviewTarget(new ChainTarget(1, 0, 0));

        List<String> ids = ids(fixture.provider.snapshot());
        Assert.assertEquals("preview-matched", ids.get(ids.size() - 2));
        Assert.assertEquals("server-area", ids.get(ids.size() - 1));

        fixture.state.setPreviewActive(false);
        Assert.assertFalse(ids(fixture.provider.snapshot()).contains("preview-matched"));
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        Assert.assertFalse(ids(fixture.provider.snapshot()).contains("server-area"));
    }

    @Test
    public void snapshotsAreImmutableAndLineIdsRemainStable() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        HudSnapshot first = fixture.provider.snapshot();
        List<String> firstIds = ids(first);

        fixture.state.setServerMatchedTargetCount(31);
        HudSnapshot second = fixture.provider.snapshot();
        Assert.assertEquals(firstIds, ids(second));
        Assert.assertNotSame(first, second);
        try {
            first.getLines().add(first.getLines().get(0));
            Assert.fail("snapshot lines must be immutable");
        } catch (UnsupportedOperationException expected) {
            // UILib 的不可变快照契约。
        }
    }

    @Test
    public void richSpansHighlightBusinessValuesWithStableIds() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setRequestedChainRadius(7);
        fixture.state.setServerChainRadius(5);
        fixture.state.setRequestedChainMaxBlocks(99);
        fixture.state.setServerChainMaxBlocks(80);
        fixture.state.setServerMatchedTargetCount(12);

        HudSnapshot snapshot = fixture.provider.snapshot();
        assertSpan(snapshot, "mode", "mode.value", "hud.qz_miner.mode.chain", HudTone.INFO);
        assertSpan(snapshot, "sub-mode", "sub-mode.value", "hud.qz_miner.sub_mode.chain.ore", HudTone.INFO);
        assertSpan(snapshot, "chain-config", "chain-config.radius-value", "7/5", HudTone.INFO);
        assertSpan(snapshot, "chain-config", "chain-config.blocks-value", "99/80", HudTone.INFO);
        assertSpan(snapshot, "server-matched", "server-matched.value", "12", HudTone.INFO);
        assertSpan(snapshot, "object-group-sync", "object-group-sync.state",
                "hud.qz_miner.sync.pending", HudTone.WARNING);
        assertNoLegacySectionStyle(snapshot);
    }

    @Test
    public void previewAndAreaExposeValueAndStateTones() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));
        fixture.preview.addPreviewTarget(new ChainTarget(1, 0, 0));

        HudSnapshot calculating = fixture.provider.snapshot();
        assertSpan(calculating, "preview-matched", "preview-matched.value", "1", HudTone.INFO);
        assertSpan(calculating, "preview-matched", "preview-matched.state",
                "hud.qz_miner.preview.calculating", HudTone.WARNING);
        assertSpan(calculating, "server-area", "server-area.volume", "72", HudTone.INFO);

        fixture.preview.setCompleted(true);
        assertSpan(fixture.provider.snapshot(), "preview-matched", "preview-matched.state",
                "hud.qz_miner.preview.completed", HudTone.SUCCESS);
    }

    private static void assertVisible(Fixture fixture, ChainPhase phase, boolean key, boolean expected) {
        fixture.projection.update(phase, fixture.generation++, 0L);
        fixture.state.setChainKeyPressed(key);
        Assert.assertEquals(phase + " key=" + key, expected, !fixture.provider.snapshot().isEmpty());
    }

    private static List<String> ids(HudSnapshot snapshot) {
        List<String> ids = new ArrayList<String>();
        for (HudLine line : snapshot.getLines()) {
            ids.add(line.getId());
        }
        return ids;
    }

    private static void assertSpan(HudSnapshot snapshot, String lineId, String spanId,
            String text, HudTone tone) {
        for (HudLine line : snapshot.getLines()) {
            if (!lineId.equals(line.getId())) {
                continue;
            }
            for (HudSpan span : line.getSpans()) {
                if (spanId.equals(span.getId())) {
                    Assert.assertEquals(text, span.getText());
                    Assert.assertEquals(tone, span.getTone());
                    return;
                }
            }
        }
        Assert.fail("missing span " + lineId + "/" + spanId);
    }

    private static void assertNoLegacySectionStyle(HudSnapshot snapshot) {
        for (HudLine line : snapshot.getLines()) {
            Assert.assertFalse(line.getText().contains("\u00a7"));
            for (HudSpan span : line.getSpans()) {
                Assert.assertFalse(span.getText().contains("\u00a7"));
            }
        }
    }

    private static final class Fixture {
        private final ChainClientState state = new ChainClientState();
        private final ClientPhaseProjection projection = new ClientPhaseProjection();
        private final ChainPreviewState preview = new ChainPreviewState();
        private int generation = 1;
        private final QzMinerHudSnapshotProvider provider = new QzMinerHudSnapshotProvider(
                state, projection, new QzMinerHudSnapshotProvider.PreviewStateSource() {
                    @Override
                    public ChainPreviewState current() {
                        return preview;
                    }
                });
    }
}
