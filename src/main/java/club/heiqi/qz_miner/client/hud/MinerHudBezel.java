package club.heiqi.qz_miner.client.hud;

import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.geometry.SceneMesh;
import club.heiqi.uilib.ui.scene.geometry.ScenePath;
import club.heiqi.uilib.ui.scene.geometry.SceneStroke;
import club.heiqi.uilib.ui.scene.geometry.SceneTransform;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;

/** 静止的压边、刻度凹槽与内盘；底衬覆盖整圈刻度，颜色始终取本批主题。 */
final class MinerHudBezel {
    private static final ScenePath SHADOW = ScenePath.ellipse(120, 121, 119, 119);
    private static final ScenePath RIM = ScenePath.ellipse(120, 120, 118.5, 118.5);
    private static final ScenePath WELL = ScenePath.ellipse(120, 120, 116, 116);
    private static final ScenePath FACE = ScenePath.ellipse(120, 120, 101.5, 101.5);
    private static final ScenePath EDGE = ScenePath.ellipse(120, 120, 118, 118);
    private static final ScenePath INNER = ScenePath.ellipse(120, 120, 102, 102);
    private static final ScenePath TOP = arc(117.5, 210, 120), BOTTOM = arc(117.5, 30, 120);
    private static final ScenePath FACE_LIGHT = arc(101, 210, 120);
    private static final SceneStroke HAIRLINE = new SceneStroke(1.1), HIGHLIGHT = new SceneStroke(1.5);
    private SceneMesh shadow, rim, well, face, edge, inner, top, bottom, faceLight;
    private double error = -1;

    private static ScenePath arc(double radius, double startDegrees, double sweepDegrees) {
        double start = Math.toRadians(startDegrees);
        return ScenePath.builder().moveTo(120 + Math.cos(start) * radius, 120 + Math.sin(start) * radius)
                .arc(120, 120, radius, start, Math.toRadians(sweepDegrees)).build();
    }

    void prepare(double nextError) {
        if (error == nextError) return;
        shadow = SHADOW.fillMesh(nextError); rim = RIM.fillMesh(nextError);
        well = WELL.fillMesh(nextError); face = FACE.fillMesh(nextError);
        edge = EDGE.strokeMesh(HAIRLINE, nextError); inner = INNER.strokeMesh(HAIRLINE, nextError);
        top = TOP.strokeMesh(HIGHLIGHT, nextError); bottom = BOTTOM.strokeMesh(HIGHLIGHT, nextError);
        faceLight = FACE_LIGHT.strokeMesh(HAIRLINE, nextError);
        error = nextError;
    }

    void paint(PaintPlanBuilder target, LayoutBox clip, SceneTransform fit,
            SceneAppearance.Palette palette, int accent, float opacity) {
        if (opacity <= 0) return;
        draw(target, clip, fit, shadow, 0x70000000, opacity);
        draw(target, clip, fit, rim, MinerHudPalette.bezel(palette), opacity);
        // 凹槽保持不透明；不能再用透明刻度直接与游戏画面的亮度竞争。
        draw(target, clip, fit, well, palette.background, opacity);
        draw(target, clip, fit, face, MinerHudPalette.face(palette, accent), opacity);
        draw(target, clip, fit, edge, SceneMotion.alpha(palette.text, palette.dark ? .2f : .24f), opacity);
        draw(target, clip, fit, top, SceneMotion.alpha(palette.text, palette.dark ? .38f : .42f), opacity);
        draw(target, clip, fit, bottom, 0x66000000, opacity);
        draw(target, clip, fit, inner, MinerHudPalette.track(palette), opacity);
        draw(target, clip, fit, faceLight, SceneMotion.alpha(palette.text, palette.dark ? .12f : .65f), opacity);
    }

    private static void draw(PaintPlanBuilder target, LayoutBox clip, SceneTransform fit,
            SceneMesh mesh, int color, float opacity) {
        target.add(PaintCommand.path(mesh.transformed(fit), clip, SceneMotion.alpha(color, opacity)));
    }
}
