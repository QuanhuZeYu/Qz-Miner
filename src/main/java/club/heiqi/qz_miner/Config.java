package club.heiqi.qz_miner;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class Config {
    public static String configPath;
    public static Configuration config;
    public static int radiusLimit = 10;
    public static int blockLimit = 1024;
    public static int perTickBlockLimit = 128;
    public static int pointFounderCacheSize = 4096;
    public static int taskTimeLimit = 16;
    public static int chainRange = 4;
    public static int getCacheTimeOut = 1000;
    public static float hunger = 1f;
    public static float overMiningDamage = 0.0003f;

    public static final String CATEGORY_CLIENT = "client";
    public static float renderLineWidth = 1.5f;
    public static float renderFadeSpeedMultiplier = 1000.0f;

    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
            sync();
        }
    }

    /**
     * 读取配置文件 - 写入到全局变量
     */
    public static void sync() {
        radiusLimit = config.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, Integer.MAX_VALUE, "挖掘者搜索范围");
        blockLimit = config.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 1024, 64, Integer.MAX_VALUE, "单次连锁挖掘方块的数量上限");
        perTickBlockLimit = config.getInt("perTickBlockLimit", Configuration.CATEGORY_GENERAL, 64, -1, Integer.MAX_VALUE, "每tick允许挖掘的方块数量，用于限制挖掘速度，无上限设置为-1即可，无上限可能会使游戏事件队列阻塞暂无动态方案，请酌情设置");
        pointFounderCacheSize = config.getInt("pointFounderCacheSize", Configuration.CATEGORY_GENERAL, 4096, 256, Integer.MAX_VALUE, "每个游戏刻中允许搜索的点上限，过高可能会导致内存溢出");
        taskTimeLimit = config.getInt("taskTimeLimit", Configuration.CATEGORY_GENERAL, 16, 5, 25, "每个游戏刻中允许执行任务的毫秒数");
        chainRange = config.getInt("chainRange", Configuration.CATEGORY_GENERAL, 4, 1, 10, "连锁挖掘的相邻半径");
        getCacheTimeOut = config.getInt("getCacheTimeOut", Configuration.CATEGORY_GENERAL, 1000, 100, Integer.MAX_VALUE, "获取缓存失败后，等待的时间");
        hunger = config.getFloat("hunger", Configuration.CATEGORY_GENERAL, 0.25f, 0.0f, 10.0f, "每次挖掘时消耗的饱食度（0.025是原版消耗值）");
        overMiningDamage = config.getFloat("overMiningDamage", Configuration.CATEGORY_GENERAL, 0.001f, 0.0f, 10.0f, "挖掘时如果饱食度不够将会对玩家造成伤害，每次空饱食度挖掘都会造成一次");

        renderLineWidth = config.getFloat("renderLineWidth", CATEGORY_CLIENT, 1.5f, 0.1f, 10.0f, "渲染线框的宽度");
        renderFadeSpeedMultiplier = config.getFloat("renderFadeSpeedMultiplier", CATEGORY_CLIENT, 10.0f, 1.0f, 100.0f, "渐变速度乘数，越大渐变速度越慢");

        config.save();
        printConfig();
    }

    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent event) {
        if (event.modID.equalsIgnoreCase(MOD_INFO.MODID)) {
            logger.info("触发保存");
            sync();
        }
    }

    /**
     * 将全局变量写入到配置文件
     */
    public static void globalVarToSave() {
        Configuration configuration = new Configuration(new File(configPath));
        configuration.get(Configuration.CATEGORY_GENERAL, "radiusLimit", 10, "挖掘者搜索范围").set(radiusLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "blockLimit", 1024, "单次连锁挖掘方块的数量上限").set(blockLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "pointFounderCacheSize", 4096, "每tick允许挖掘的方块数量，用于限制挖掘速度，无上限设置为-1即可，无上限可能会使游戏事件队列阻塞暂无动态方案，请酌情设置").set(pointFounderCacheSize);
        configuration.get(Configuration.CATEGORY_GENERAL, "taskTimeLimit", 16, "每个游戏刻中允许搜索的点上限，过高可能会导致内存溢出").set(taskTimeLimit);
        configuration.get(Configuration.CATEGORY_GENERAL, "chainRange", 4, "连锁挖掘的相邻半径").set(chainRange);
        configuration.get(Configuration.CATEGORY_GENERAL, "getCacheTimeOut", 1000, "获取缓存失败后，等待的时间").set(getCacheTimeOut);
        configuration.get(Configuration.CATEGORY_GENERAL, "hunger", 0.25f, "每次挖掘时消耗的饱食度（0.025是原版消耗值）").set(hunger);
        configuration.get(Configuration.CATEGORY_GENERAL, "overMiningDamage", 0.001f, "挖掘时如果饱食度不够将会对玩家造成伤害，每次空饱食度挖掘都会造成一次").set(overMiningDamage);

        configuration.get(CATEGORY_CLIENT, "renderLineWidth", 1.5f, "渲染线框的宽度").set(renderLineWidth);
        configuration.get(CATEGORY_CLIENT, "renderFadeSpeedMultiplier", 10.0f, "渐变速度乘数，越大渐变速度越慢").set(renderFadeSpeedMultiplier);
    }

    public static void printConfig() {
        Configuration configuration = new Configuration(new File(configPath));
        int radiusLimit = configuration.getInt("radiusLimit", Configuration.CATEGORY_GENERAL, 10, 1, Integer.MAX_VALUE, "挖掘者搜索范围");
        int blockLimit = configuration.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 1024, 64, Integer.MAX_VALUE, "单次连锁挖掘方块的数量上限");
        int pointFounderCacheSize = configuration.getInt("pointFounderCacheSize", Configuration.CATEGORY_GENERAL, 4096, 256, Integer.MAX_VALUE, "每个游戏刻中允许搜索的点上限，过高可能会导致内存溢出");
        int taskTimeLimit = configuration.getInt("taskTimeLimit", Configuration.CATEGORY_GENERAL, 16, 5, 25, "每个游戏刻中允许执行任务的毫秒数");
        int chainRange = configuration.getInt("chainRange", Configuration.CATEGORY_GENERAL, 4, 1, 10, "连锁挖掘的相邻半径");
        int getCacheTimeOut = configuration.getInt("getCacheTimeOut", Configuration.CATEGORY_GENERAL, 1000, 100, Integer.MAX_VALUE, "获取缓存失败后，等待的时间");
        float hunger = configuration.getFloat("hunger", Configuration.CATEGORY_GENERAL, 0.25f, 0.0f, 10.0f, "每次挖掘时消耗的饱食度（0.025是原版消耗值）");
        float overMiningDamage = configuration.getFloat("overMiningDamage", Configuration.CATEGORY_GENERAL, 0.001f, 0.0f, 10.0f, "挖掘时如果饱食度不够将会对玩家造成伤害，每次空饱食度挖掘都会造成一次");

        float renderLineWidth = (float) configuration.get(CATEGORY_CLIENT, "renderLineWidth", 1.5f).getDouble();
        float renderFadeSpeedMultiplier = (float) configuration.get(CATEGORY_CLIENT, "renderFadeSpeedMultiplier", 10.0f).getDouble();
        logger.info("radiusLimit: {}, blockLimit: {}, pointFounderCacheSize: {}, taskTimeLimit: {}, chainRange: {}, getCacheTimeOut: {}, hunger: {}, overMiningDamage: {}, renderLineWidth: {}, renderFadeSpeedMultiplier: {}",
            radiusLimit, blockLimit, pointFounderCacheSize, taskTimeLimit, chainRange, getCacheTimeOut, hunger, overMiningDamage, renderLineWidth, renderFadeSpeedMultiplier);
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
