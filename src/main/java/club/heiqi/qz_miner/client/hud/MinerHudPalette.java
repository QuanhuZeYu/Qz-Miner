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
    static int warning(SceneAppearance.Palette palette) { return palette.dark ? 0xfffdba74 : 0xff9a3412; }
}
