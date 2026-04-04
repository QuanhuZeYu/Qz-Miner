package club.heiqi.qz_miner.client;

import net.minecraft.util.StatCollector;

/**
 * 客户端文本国际化工具。
 */
public final class ClientI18n {

    private ClientI18n() {}

    /**
     * 翻译指定语言键。
     *
     * @param key 语言键
     * @param args 格式化参数
     * @return 翻译结果
     */
    public static String tr(String key, Object... args) {
        if (args == null || args.length == 0) {
            return StatCollector.translateToLocal(key);
        }
        return StatCollector.translateToLocalFormatted(key, args);
    }
}
