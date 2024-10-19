package club.heiqi.qz_miner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraftforge.common.config.Configuration;

public class Config {
    public static String configFile;
    public static int radiusLimit = 10;
    public static int blockLimit = 1024;
    public static int chainRange = 3;
    public static List<List<String>> sameBlockNameList = new ArrayList<>();

    public static void sync(File configFile) {
        Config.configFile = configFile.getAbsolutePath();
        Configuration configuration = new Configuration(configFile);

        radiusLimit = configuration.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, 128, "挖掘者搜索范围");
        blockLimit = configuration.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 128, 1, 40960, "一次性最多挖掘数量");
        chainRange = configuration.getInt("chainRange", Configuration.CATEGORY_GENERAL, 3, 1, 6, "连锁模式搜索相邻范围");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void save() {
        Configuration configuration = new Configuration(new File(configFile));
        configuration.get(Configuration.CATEGORY_GENERAL, "radiusLimit", radiusLimit).set(radiusLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "blockLimit", blockLimit).set(blockLimit);
        configuration.save();
    }

    /**
     * 将字符串列表转换为Block列表
     * @param nameList
     * @return
     */
    public static List<Block> loadSameBlockList(List<String> nameList) {
        List<Block> sameBlockList = new ArrayList<>();
        for(String name : nameList) {
            Block block = Block.getBlockFromName(name);
            if(block != null) {
                sameBlockList.add(block);
            }
        }
        return sameBlockList;
    }
}
