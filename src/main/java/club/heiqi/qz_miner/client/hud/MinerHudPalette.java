package club.heiqi.qz_miner.client.hud;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;

/** 模式身份与任务警示独立；浅色主题使用深色墨迹，子模式沿所属主模式的色相。 */
final class MinerHudPalette {
    private MinerHudPalette() { }
    static int mode(ChainMode mode, SceneAppearance.Palette palette) {
        if (mode == null) return palette.muted;
        switch (mode) {
            case CHAIN: return palette.dark ? 0xff67e8f9 : 0xff0e7490;
            case AREA: return palette.dark ? 0xfffcd34d : 0xff925500;
            case INTERACT: return palette.dark ? 0xff6ee7b7 : 0xff047857;
            case SPECIAL: return palette.dark ? 0xffc4b5fd : 0xff6d28d9;
            default: return palette.text;
        }
    }
    static int secondary(ChainMode mode, SceneAppearance.Palette palette) {
        return SceneMotion.alpha(mode(mode, palette), palette.dark ? .9f : 1f);
    }
    static int subtitle(ChainMode mode, SceneAppearance.Palette palette) {
        return tint(palette.muted, mode(mode, palette), .32f);
    }
    static int bezel(SceneAppearance.Palette palette) { return tint(palette.surface, palette.text, .16f); }
    static int face(SceneAppearance.Palette palette, int accent) { return tint(palette.surface, accent, palette.dark ? .045f : .025f); }
    static int track(SceneAppearance.Palette palette) { return tint(palette.surface, palette.muted, .36f); }
    static int tick(SceneAppearance.Palette palette, int accent, int level) {
        if (level == 3) return accent;
        return tint(palette.muted, palette.text, level == 2 ? .68f : level == 1 ? .3f : 0);
    }
    static int highlight(int color, SceneAppearance.Palette palette, float glint) {
        return tint(color, palette.text, .25f * glint);
    }
    // 本地材质只混合不透明主题颜料；有限淡入及颜色轨迹仍由 Core 的正式入口负责。
    private static int tint(int base, int ink, float amount) {
        int red = Math.round((base >>> 16 & 255) * (1 - amount) + (ink >>> 16 & 255) * amount);
        int green = Math.round((base >>> 8 & 255) * (1 - amount) + (ink >>> 8 & 255) * amount);
        int blue = Math.round((base & 255) * (1 - amount) + (ink & 255) * amount);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }
    static int warning(SceneAppearance.Palette palette) { return palette.dark ? 0xfffdba74 : 0xff9a3412; }
}
