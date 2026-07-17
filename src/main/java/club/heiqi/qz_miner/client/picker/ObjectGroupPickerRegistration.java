package club.heiqi.qz_miner.client.picker;

import club.heiqi.config.ui.editor.Registry;

/** 为每个配置 screen 注册独立的方块搜索 Picker。 */
public final class ObjectGroupPickerRegistration {
    private ObjectGroupPickerRegistration() { }

    public static void register(Registry registry) {
        registry.register(new BlockPickerProvider(BlockVariantEnumerator.enumerate()));
    }
}
