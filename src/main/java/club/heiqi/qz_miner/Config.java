package club.heiqi.qz_miner;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;

public class Config {
    public static String configPath;
    public static Configuration config;
    public static int radiusLimit = 10;
    public static int blockLimit = 1024;
    public static int perTickBlockLimit = 128;
    public static int taskTimeLimit = 16;
    public static int neighborDistance = 4;
    public static int heartbeatTimeout = 5_000; // 5s
    public static float exhaustion = 0.025f;
    public static float overMiningDamage = 0.0003f;
    public static int maxFortuneLevel = 3;
    public static float dropDistance = 1.1f;
    public static float coolDown = 30.0f;
    public static boolean forceNatural = false;
    public static boolean dropItemToSelf = true;
    /**
     * 存放格式: {
     *     {
     *          stringID: minecraft:dirt,
     *          blockMeta: 0,
     *          mID: 0 {@code 可指代greg的mID或者bart-work的mID等Tile内存放的meta值}
     *     },
     * }
     */
    public static String[] whiteList = {
        "stringID:minecraft:dirt,blockMeta:0,mID:0",
        "stringID:minecraft:stone,blockMeta:0,mID:0"
    };

    public static final String CATEGORY_CLIENT = "client";
    public static float renderLineWidth = 1.5f;
    public static float renderFadeSpeedMultiplier = 0.5f;
    public static int renderCount = 10240;
    public static boolean printResult = true;
    public static boolean showTip = true;
    public static boolean useRender = true;

    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
            sync();
        }
    }

    public static void walkMap(Consumer<Property> handler) {
        Property radiusLimit = config.get(Configuration.CATEGORY_GENERAL,"radiusLimit",10,"最大搜索范围", 1, Integer.MAX_VALUE);
        Property blockLimit = config.get(Configuration.CATEGORY_GENERAL,"blockLimit",1024,"连锁上限", 1, Integer.MAX_VALUE);
        Property perTickBlockLimit = config.get(Configuration.CATEGORY_GENERAL,"perTickBlockLimit",64,"每tick挖掘限制数量",1,Integer.MAX_VALUE);
        Property taskTimeLimit = config.get(Configuration.CATEGORY_GENERAL,"taskTimeLimit",10, "每tick(50ms)内任务运行执行的时间 /ms", 10, Integer.MAX_VALUE);
        Property neighborDistance = config.get(Configuration.CATEGORY_GENERAL,"neighborDistance",3, "连锁邻居探测距离", 1, Integer.MAX_VALUE);
        Property heartbeatTimeout = config.get(Configuration.CATEGORY_GENERAL,"heartbeatTimeout",1_000, "任务心跳超时时间", 100, Integer.MAX_VALUE);
        Property exhaustion = config.get(Configuration.CATEGORY_GENERAL,"exhaustion",0.025f, "每次挖掘消耗饱食度", 0f, Float.MAX_VALUE);
        Property overMiningDamage = config.get(Configuration.CATEGORY_GENERAL,"overMiningDamage",0.0001f, "饱食度过低时每次挖掘消耗的生命值", 0f, Float.MAX_VALUE);
        Property maxFortuneLevel = config.get(Configuration.CATEGORY_GENERAL,"maxFortuneLevel",3 , "矿石接受的最大时运等级", 3, 255);
        Property dropDistance = config.get(Configuration.CATEGORY_GENERAL,"dropDistance",1.1f, "挖掘时掉落物距离自身的距离，值越大掉落物越远", 0f, Float.MAX_VALUE);
        Property coolDown = config.get(Configuration.CATEGORY_GENERAL,"coolDown",30.f, "每次揭示时需要等待的时间，单位秒", 0f, Float.MAX_VALUE);
        Property forceNatural = config.get(Configuration.CATEGORY_GENERAL,"forceNatural",false, "强制所有矿石为时运");
        Property dropItemToSelf = config.get(Configuration.CATEGORY_GENERAL,"dropItemToSelf",true, "是否将掉落物生成在脚下");
        Property whiteList = config.get(Configuration.CATEGORY_GENERAL,"whiteList",Config.whiteList,"连锁组白名单列表");

        Property renderLineWidth = config.get(Config.CATEGORY_CLIENT,"renderLineWidth",1.5f, "渲染线框宽度", 0.1f, 100f);
        Property renderFadeSpeedMultiplier = config.get(Config.CATEGORY_CLIENT,"renderFadeSpeedMultiplier",0.5f, "渲染变化参数种子，改为0或负数即可显示为白色", 0.f, Float.MAX_VALUE);
        Property renderCount = config.get(Config.CATEGORY_CLIENT,"renderCount",64, "渲染数量上限", -1, Integer.MAX_VALUE);
        Property printResult = config.get(Config.CATEGORY_CLIENT,"printResult",true, "在聊天栏打印挖掘结果");
        Property showTip = config.get(Config.CATEGORY_CLIENT,"showTip",true, "是否显示左下角提示");
        Property useRender = config.get(Config.CATEGORY_CLIENT,"useRender",true, "是否使用渲染功能");

        List<Property> properties = new ArrayList<>();
        properties.add(radiusLimit);
        properties.add(blockLimit);
        properties.add(perTickBlockLimit);
        properties.add(taskTimeLimit);
        properties.add(neighborDistance);
        properties.add(heartbeatTimeout);
        properties.add(exhaustion);
        properties.add(overMiningDamage);
        properties.add(maxFortuneLevel);
        properties.add(dropDistance);
        properties.add(coolDown);
        properties.add(forceNatural);
        properties.add(dropItemToSelf);
        properties.add(whiteList);

        properties.add(renderLineWidth);
        properties.add(renderFadeSpeedMultiplier);
        properties.add(renderCount);
        properties.add(printResult);
        properties.add(showTip);
        properties.add(useRender);

        for (Property property : properties)
            handler.accept(property);
    }

    /**
     * 读取配置文件 - 写入到全局变量
     */
    public static void sync() {
        walkMap(property -> {
            String filedName = property.getName();
            try {
                Field field = Config.class.getField(filedName);
                Object value = field.get(null);
                if (Objects.equals(property.getName(), filedName)) {
                    if (field.getType() == int.class) field.setInt(null,property.getInt());
                    else if (field.getType() == double.class) field.setDouble(null,property.getDouble());
                    else if (field.getType() == float.class) field.setFloat(null, (float) property.getDouble());
                    else if (field.getType() == boolean.class) field.setBoolean(null,property.getBoolean());
                    else if (field.getType() == String.class) field.set(null,property.getString());
                    else if (field.getType() == String[].class) field.set(null,property.getStringList());
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });

        config.save();
    }

    public static void save() {
        config.save();
    }

    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent event) {
        if (event.modID.equalsIgnoreCase(MOD_INFO.MODID)) {
            sync();
        }
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

}
