package club.heiqi.qz_miner.Util;

import club.heiqi.qz_miner.MY_LOG;

public class CheckCompatibility {
    public static boolean isHasClass_BlockOresAbstract = false;

    public static void isHasClass_BlockOresAbstract() {
        try {
            Class<?> clazz = Class.forName("gregtech.common.blocks.BlockOresAbstract");
            isHasClass_BlockOresAbstract = true;
        } catch (ClassNotFoundException e) {
            MY_LOG.logger.warn("未检测到 粗矿类");
            isHasClass_BlockOresAbstract = false;
        }
    }
}
