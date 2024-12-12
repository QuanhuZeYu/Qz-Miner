package club.heiqi.qz_miner;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class Config {
    public static String configFile;
    public static int radiusLimit = 10;
    public static int blockLimit = 1024;
    public static int pointFounderCacheSize = 4096;
    public static int taskTimeLimit = 16;
    public static int chainRange = 4;
    public static int getCacheTimeOut = 1000;
    public static float hunger = 1f;
    public static float overMiningDamage = 0.0003f;

    public static Configuration config;

    public static void sync(File configFile) {
        Config.configFile = configFile.getAbsolutePath();
        config = new Configuration(configFile);

        radiusLimit = config.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, Integer.MAX_VALUE, "挖掘者搜索范围");
        blockLimit = config.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 1024, 64, Integer.MAX_VALUE, "单次连锁挖掘方块的数量上限");
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

    public static void save() {
        Configuration configuration = new Configuration(new File(configFile));
        configuration.get(Configuration.CATEGORY_GENERAL, "radiusLimit", radiusLimit).set(radiusLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "blockLimit", blockLimit).set(blockLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "pointFounderCacheSize", pointFounderCacheSize).set(pointFounderCacheSize);
        configuration.get(Configuration.CATEGORY_GENERAL, "taskTimeLimit", taskTimeLimit).set(taskTimeLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "chainRange", chainRange).set(chainRange);
        configuration.get(Configuration.CATEGORY_GENERAL, "getCacheTimeOut", getCacheTimeOut).set(getCacheTimeOut);
        configuration.get(Configuration.CATEGORY_GENERAL, "hunger", hunger).set(hunger);
        configuration.get(Configuration.CATEGORY_GENERAL, "overMiningDamage", overMiningDamage).set(overMiningDamage);
        configuration.save();
    }
}
