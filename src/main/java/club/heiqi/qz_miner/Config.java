package club.heiqi.qz_miner;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class Config {
    public static String configFile;
    public static Configuration config;
    public static int radiusLimit = 10;
    public static int blockLimit = 1024;
    public static int perTickBlockLimit = 64;
    public static int pointFounderCacheSize = 4096;
    public static int taskTimeLimit = 16;
    public static int chainRange = 4;
    public static int getCacheTimeOut = 1000;
    public static float hunger = 1f;
    public static float overMiningDamage = 0.0003f;

    /**
     * 读取配置文件 - 写入到全局变量
     * @param configFile
     */
    public static void sync(File configFile) {
        Config.configFile = configFile.getAbsolutePath();
        config = new Configuration(configFile);

        radiusLimit = config.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, Integer.MAX_VALUE, "挖掘者搜索范围");
        blockLimit = config.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 1024, 64, Integer.MAX_VALUE, "单次连锁挖掘方块的数量上限");
        perTickBlockLimit = config.getInt("perTickBlockLimit", Configuration.CATEGORY_GENERAL, 64, -1, Integer.MAX_VALUE, "每tick允许挖掘的方块数量，用于限制挖掘速度，无上限设置为-1即可此时每tick挖掘数量会由taskLimit进行动态限制以保证游戏流畅运行");
        pointFounderCacheSize = config.getInt("pointFounderCacheSize", Configuration.CATEGORY_GENERAL, 4096, 256, Integer.MAX_VALUE, "每个游戏刻中允许搜索的点上限，过高可能会导致内存溢出");
        taskTimeLimit = config.getInt("taskTimeLimit", Configuration.CATEGORY_GENERAL, 16, 5, 25, "每个游戏刻中允许执行任务的毫秒数");
        chainRange = config.getInt("chainRange", Configuration.CATEGORY_GENERAL, 4, 1, 10, "连锁挖掘的相邻半径");
        getCacheTimeOut = config.getInt("getCacheTimeOut", Configuration.CATEGORY_GENERAL, 1000, 100, Integer.MAX_VALUE, "获取缓存失败后，等待的时间");
        hunger = config.getFloat("hunger", Configuration.CATEGORY_GENERAL, 0.25f, 0.0f, 10.0f, "每次挖掘时消耗的饱食度（0.025是原版消耗值）");
        overMiningDamage = config.getFloat("overMiningDamage", Configuration.CATEGORY_GENERAL, 0.001f, 0.0f, 10.0f, "挖掘时如果饱食度不够将会对玩家造成伤害，每次空饱食度挖掘都会造成一次");

        if (config.hasChanged()) {
            config.save();
        }
    }

    /**
     * 将全局变量写入到配置文件
     */
    public static void save() {
        Configuration configuration = new Configuration(new File(configFile));
        configuration.get(Configuration.CATEGORY_GENERAL, "radiusLimit", 10).set(radiusLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "blockLimit", 1024).set(blockLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "pointFounderCacheSize", 4096).set(pointFounderCacheSize);
        configuration.get(Configuration.CATEGORY_GENERAL, "taskTimeLimit", 16).set(taskTimeLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "chainRange", 4).set(chainRange);
        configuration.get(Configuration.CATEGORY_GENERAL, "getCacheTimeOut", 1000).set(getCacheTimeOut);
        configuration.get(Configuration.CATEGORY_GENERAL, "hunger", 0.25f).set(hunger);
        configuration.get(Configuration.CATEGORY_GENERAL, "overMiningDamage", 0.001f).set(overMiningDamage);
        configuration.save();
    }
}
