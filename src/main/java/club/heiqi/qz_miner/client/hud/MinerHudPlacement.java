package club.heiqi.qz_miner.client.hud;

import java.util.LinkedHashMap;
import java.util.Map;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.scene.hud.SceneHudPlacement;

/** 默认值只取生产 schema；通用 HUD 位置与本机文档之间的业务映射。 */
public final class MinerHudPlacement {
    private MinerHudPlacement() { }
    public static SceneHudPlacement read(ConfigDocument document) {
        return new SceneHudPlacement(SceneHudPlacement.Anchor.valueOf(document.get(QzMinerConfigSchema.HUD_ANCHOR)),
                document.get(QzMinerConfigSchema.HUD_OFFSET_X).floatValue(),
                document.get(QzMinerConfigSchema.HUD_OFFSET_Y).floatValue(),
                document.get(QzMinerConfigSchema.HUD_SCALE).floatValue());
    }
    public static SceneHudPlacement defaults() { return read(ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA)); }
    public static Map<ConfigKey<?>, Object> updates(ConfigDocument baseline, SceneHudPlacement placement) {
        SceneHudPlacement initial = read(baseline);
        Map<ConfigKey<?>, Object> values = new LinkedHashMap<ConfigKey<?>, Object>();
        values.put(QzMinerConfigSchema.HUD_ANCHOR, placement.anchor.name());
        values.put(QzMinerConfigSchema.HUD_OFFSET_X, component(baseline, QzMinerConfigSchema.HUD_OFFSET_X, initial.offsetX, placement.offsetX));
        values.put(QzMinerConfigSchema.HUD_OFFSET_Y, component(baseline, QzMinerConfigSchema.HUD_OFFSET_Y, initial.offsetY, placement.offsetY));
        values.put(QzMinerConfigSchema.HUD_SCALE, component(baseline, QzMinerConfigSchema.HUD_SCALE, initial.scale, placement.scale));
        return values;
    }

    /** 未改变的 float 展示投影保留原 Double 权威；变化才使用可往返的 float 规范文本。 */
    private static Double component(ConfigDocument baseline, ConfigKey<Double> key, float initial, float current) {
        return Float.compare(initial, current) == 0 ? baseline.get(key) : Double.valueOf(Float.toString(current));
    }
}
