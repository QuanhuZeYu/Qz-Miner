package club.heiqi.qz_miner.client.hud;

import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.motion.EntranceOptions;
import club.heiqi.uilib.ui.scene.motion.MotionSpec;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;

/** 表盘的出场节奏；只声明局部策略，计时、降级和卸载仍归 Core。 */
final class MinerHudEntrance {
    static final MotionSpec RING = MotionSpec.tween(520_000_000L, MotionSpec.LINEAR);
    static final MotionSpec CENTER = reveal(20, 190);
    static final MotionSpec VERTICAL = reveal(65, 220);
    static final MotionSpec HORIZONTAL = reveal(95, 230);
    static final MotionSpec STATUS = reveal(110, 190);
    static final MotionSpec DETAILS = reveal(145, 235);

    private MinerHudEntrance() { }

    static NodeBinding enter(ScenePageContext page, MotionSpec feedback,
            ReadableSignal<Float> offsetX, ReadableSignal<Float> offsetY) {
        return SceneMotion.enter(() -> new EntranceOptions(page.theme().get().spatialMotion,
                feedback, offsetX.get(), offsetY.get()));
    }

    private static MotionSpec reveal(long delayMillis, long durationMillis) {
        return MotionSpec.tween((delayMillis + durationMillis) * 1_000_000L, time -> {
            double value = Math.max(0, Math.min(1, (time * (delayMillis + durationMillis) - delayMillis) / durationMillis));
            return 1 - Math.pow(1 - value, 3);
        });
    }
}
