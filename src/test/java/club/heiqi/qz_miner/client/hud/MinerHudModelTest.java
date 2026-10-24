package club.heiqi.qz_miner.client.hud;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;

public class MinerHudModelTest {
    @Test public void unchangedFactsReuseTextAndExecutionSamplingStaysAnEstimate() {
        ReactiveDomain domain = ReactiveDomain.direct();
        domain.execute(() -> {
            AtomicInteger translations = new AtomicInteger();
            MinerHudModel model = new MinerHudModel(domain, key -> { translations.incrementAndGet(); return key; });
            ChainClientState state = new ChainClientState();
            model.context(state, true, "en_US");
            String heading = model.heading.get();
            int before = translations.get();
            model.context(state, true, "en_US");
            Assert.assertEquals(before, translations.get());
            Assert.assertSame(heading, model.heading.get());
            ClientPhaseProjection phase = new ClientPhaseProjection();
            phase.update(ChainPhase.RUNNING, 1, 10);
            ChainPreviewPresentationProjection preview = new ChainPreviewPresentationProjection();
            ChainPreviewState targets = new ChainPreviewState();
            int generation = targets.begin(new club.heiqi.qz_miner.chain.planner.ChainTarget(1, 2, 3));
            targets.addPreviewTarget(generation, new club.heiqi.qz_miner.chain.planner.ChainTarget(1, 2, 3));
            model.header(preview.sampleAndPublish(targets, null, phase,
                    1L, 1L, 0L, 1L, 1L, false, true, 0, ChainPreviewBackendDiagnostics.DISABLED));
            Assert.assertTrue(model.rows.get().stream().anyMatch(row -> row.id.equals("estimate")
                    && row.label.equals("hud.qz_miner.preview.estimate") && row.value.equals("0 / 1")));
            Assert.assertFalse(model.rows.get().stream().anyMatch(row -> row.id.equals("backend")));
            Assert.assertFalse("零匹配确认不占用 HUD", model.rows.get().stream().anyMatch(row -> row.id.equals("server")));
            model.header(null);
        });
    }
}
