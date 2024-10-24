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
    public static int perTickBlockMine = 64;
    public static int pointFounderCacheSize = 4096;
    public static int chainRange = 3;
    public static int pointFonderTaskTimeLimit = 5;

    public static void sync(File configFile) {
        Config.configFile = configFile.getAbsolutePath();
        Configuration configuration = new Configuration(configFile);

        radiusLimit = configuration.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, Integer.MAX_VALUE, "挖掘者搜索范围");
        blockLimit = configuration.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 128, 1, Integer.MAX_VALUE, "一次性最多挖掘数量");
        perTickBlockMine = configuration.getInt("perTickBlockMine", Configuration.CATEGORY_GENERAL, 64, 1, Integer.MAX_VALUE, "每tick挖掘数量");
        pointFounderCacheSize = configuration.getInt("pointFounderCacheSize", Configuration.CATEGORY_GENERAL, 4096, 1, 10240, "点位生成器缓存大小, 不建议超过建议最大数值");
        chainRange = configuration.getInt("chainRange", Configuration.CATEGORY_GENERAL, 3, 1, 6, "连锁模式搜索相邻范围");
        pointFonderTaskTimeLimit = configuration.getInt("pointFonderTaskTimeLimit", Configuration.CATEGORY_GENERAL, 5, 1, 20, "连锁遍历点每tick允许搜寻最大时间限制 单位ms");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void save() {
        Configuration configuration = new Configuration(new File(configFile));
        configuration.get(Configuration.CATEGORY_GENERAL, "radiusLimit", radiusLimit).set(radiusLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "blockLimit", blockLimit).set(blockLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "perTickBlockMine", perTickBlockMine).set(perTickBlockMine);
        configuration.get(Configuration.CATEGORY_GENERAL, "pointFounderCacheSize", pointFounderCacheSize).set(pointFounderCacheSize);
        configuration.get(Configuration.CATEGORY_GENERAL, "chainRange", chainRange).set(chainRange);
        configuration.get(Configuration.CATEGORY_GENERAL, "pointFonderTaskTimeLimit", pointFonderTaskTimeLimit).set(pointFonderTaskTimeLimit);
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
