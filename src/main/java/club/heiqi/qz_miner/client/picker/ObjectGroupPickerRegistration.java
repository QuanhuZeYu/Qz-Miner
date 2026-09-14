package club.heiqi.qz_miner.client.picker;

import club.heiqi.config.ui.editor.Registry;

/**
 * 为每个配置 screen 注册方块搜索 Picker。
 *
 * <p>契约出处：（ADR 原稿在工作站、不在仓内） §1.4「注册期冻结语义」/§1.7 D-5：
 * 注册只固化<b>惰性候选源的引用</b>，<b>禁止</b>在注册/构造期复制候选数据。候选清单在首次真实读取时
 * 才捕获（{@link BlockPickerCandidateSource#getInstance()} 的进程级单例 + 脏标记惰性重建）。</p>
 */
public final class ObjectGroupPickerRegistration {
    private ObjectGroupPickerRegistration() { }

    /**
     * 注册惰性 provider（零枚举、零物化）。
     *
     * @param registry 当前 screen 的 value editor registry
     */
    public static void register(Registry registry) {
        registry.register(new BlockPickerProvider(BlockPickerCandidateSource.getInstance()));
    }
}
