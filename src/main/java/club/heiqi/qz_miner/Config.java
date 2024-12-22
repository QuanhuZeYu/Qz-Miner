package club.heiqi.qz_miner;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;

import static club.heiqi.qz_miner.MY_LOG.LOG;

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
    public static String[] chainGroup = new String[]{"{id:\"minecraft:stone\", meta:0}", "{id:\"minecraft:dirt\"}"};

    public static final String CATEGORY_CLIENT = "client";
    public static float renderLineWidth = 1.5f;
    public static float renderFadeSpeedMultiplier = 1000.0f;
    public static boolean printResult = true;
    public static float renderTime = 8f;
    public static float renderDistance = 4.5f;
    public static boolean cullRender = false;

    public static Multimap<String, Object> configMap = ArrayListMultimap.create();
    static {
        configMap.put("radiusLimit", radiusLimit);                              configMap.put("radiusLimit", Configuration.CATEGORY_GENERAL);           configMap.put("radiusLimit", 10);                               configMap.put("radiusLimit", "挖掘者搜索范围");                                                                             configMap.put("radiusLimit", 1);                          configMap.put("radiusLimit", Integer.MAX_VALUE);
        configMap.put("blockLimit", blockLimit);                                configMap.put("blockLimit", Configuration.CATEGORY_GENERAL);            configMap.put("blockLimit", 1024);                              configMap.put("blockLimit", "一次连锁挖掘方块的数量上限");                                                                    configMap.put("blockLimit", 64);                          configMap.put("blockLimit", Integer.MAX_VALUE);
        configMap.put("perTickBlockLimit", perTickBlockLimit);                  configMap.put("perTickBlockLimit", Configuration.CATEGORY_GENERAL);     configMap.put("perTickBlockLimit", 64);                         configMap.put("perTickBlockLimit", "每tick挖掘方块的数量上限，用于限制挖掘速度，无上限设为-1即可，无上限可能导致游戏卡顿，请酌情设置");   configMap.put("perTickBlockLimit", -1);                   configMap.put("perTickBlockLimit", Integer.MAX_VALUE);
        configMap.put("pointFounderCacheSize", pointFounderCacheSize);          configMap.put("pointFounderCacheSize", Configuration.CATEGORY_GENERAL); configMap.put("pointFounderCacheSize", 4096);                   configMap.put("pointFounderCacheSize", "搜索队列允许缓存点数量，实现方案为多线程阻塞队列，如果你知道这是什么可以随意调整");             configMap.put("pointFounderCacheSize", 256);              configMap.put("pointFounderCacheSize", Integer.MAX_VALUE);
        configMap.put("taskTimeLimit", taskTimeLimit);                          configMap.put("taskTimeLimit", Configuration.CATEGORY_GENERAL);         configMap.put("taskTimeLimit", 8);                              configMap.put("taskTimeLimit", "每个游戏刻连锁任务允许允许的时间，请不要设置过低或大于30可能会影响游戏流畅度或使连锁无法正常工作");        configMap.put("taskTimeLimit", 5);                        configMap.put("taskTimeLimit", 25);
        configMap.put("chainRange", chainRange);                                configMap.put("chainRange", Configuration.CATEGORY_GENERAL);            configMap.put("chainRange", 3);                                 configMap.put("chainRange", "连锁挖掘判定相邻半径");                                                                         configMap.put("chainRange", 1);                           configMap.put("chainRange", 5);
        configMap.put("getCacheTimeOut", getCacheTimeOut);                      configMap.put("getCacheTimeOut", Configuration.CATEGORY_GENERAL);       configMap.put("getCacheTimeOut", 1000);                         configMap.put("getCacheTimeOut", "挖掘任务获取缓存队列超时时间");                                                              configMap.put("getCacheTimeOut", 100);                    configMap.put("getCacheTimeOut", Integer.MAX_VALUE);
        configMap.put("hunger", hunger);                                        configMap.put("hunger", Configuration.CATEGORY_GENERAL);                configMap.put("hunger", 0.25f);                                 configMap.put("hunger", "每次挖掘时消耗的饱食度(参考:0.025是原版值)");                                                          configMap.put("hunger", 0.0f);                            configMap.put("hunger", 2.0f);
        configMap.put("overMiningDamage", overMiningDamage);                    configMap.put("overMiningDamage", Configuration.CATEGORY_GENERAL);      configMap.put("overMiningDamage", 0.001f);                      configMap.put("overMiningDamage", "挖掘时如果饱食度不足将会造成伤害，每次空饱食度挖掘都会造成一次伤害");                              configMap.put("overMiningDamage", 0.0f);                  configMap.put("overMiningDamage", 2.0f);
        configMap.put("printResult", printResult);                              configMap.put("printResult", CATEGORY_CLIENT);                          configMap.put("printResult", true);                             configMap.put("printResult", "在聊天栏打印挖掘结果");
        configMap.put("renderLineWidth", renderLineWidth);                      configMap.put("renderLineWidth", CATEGORY_CLIENT);                      configMap.put("renderLineWidth", 1.5f);                         configMap.put("renderLineWidth", "渲染线框宽度");                                                                           configMap.put("renderLineWidth", 0.1f);                   configMap.put("renderLineWidth", 10.0f);
        configMap.put("renderFadeSpeedMultiplier", renderFadeSpeedMultiplier);  configMap.put("renderFadeSpeedMultiplier", CATEGORY_CLIENT);            configMap.put("renderFadeSpeedMultiplier", 50.0f);              configMap.put("renderFadeSpeedMultiplier", "渲染框颜色变化时间乘数，越大越慢");                                                 configMap.put("renderFadeSpeedMultiplier", 10.0f);        configMap.put("renderFadeSpeedMultiplier", Float.MAX_VALUE);
        configMap.put("renderTime", renderTime);                                configMap.put("renderTime", CATEGORY_CLIENT);                           configMap.put("renderTime", 8f);                                configMap.put("renderTime", "每帧渲染选择结果允许的时长，过短可能会导致闪烁或者无法显示，如果需要关闭渲染功能，设置为-1即可");             configMap.put("renderTime", -1f);                          configMap.put("renderTime", 15f);
        configMap.put("renderDistance", renderDistance);                        configMap.put("renderDistance", CATEGORY_CLIENT);                       configMap.put("renderDistance", 4.5f);                          configMap.put("renderDistance", "选择渲染框选择点最大距离");                                                                  configMap.put("renderDistance", 3f);                      configMap.put("renderDistance", 15f);
        configMap.put("cullRender", cullRender);                                configMap.put("cullRender", CATEGORY_CLIENT);                           configMap.put("cullRender", false);                             configMap.put("cullRender", "是否剔除重合边");
        // 用于传递进去判断类型                                                     // 分类
    }

    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
            sync();
        }
    }

    public static void walkMap(Consumer<ConfigFieldData> handler) {
        Iterator<String> iterator = configMap.keySet().iterator();
        Class<Config> clazz = Config.class;
        while (iterator.hasNext()) {
            String name = iterator.next();
            Collection<Object> value = configMap.get(name);
            List<Object> values = new ArrayList<>(value);
            Object staticValue = values.get(0); // 用于获取类型
            Field field = null;
            try {
                field = clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
            String category = (String) values.get(1);
            Object defaultValue = values.get(2);
            String description = (String) values.get(3);
            Object minValue = new Object(), maxValue = new Object();
            if (values.size() > 4) {
                minValue = values.get(4);
                maxValue = values.get(5);
            }
            // 在此处可以插入自定义的函数处理上述内容
            ConfigFieldData fieldData = new ConfigFieldData(field, name, staticValue, category, defaultValue, description, minValue, maxValue);
            handler.accept(fieldData); // 回调
        }
    }

    /**
     * 读取配置文件 - 写入到全局变量
     */
    public static void sync() {
        walkMap(f -> {
            try {
                if (f.staticValue instanceof Integer) {
                    f.field.set(null, config.getInt(f.name, f.category, (Integer) f.defaultValue, (Integer) f.minValue, (Integer) f.maxValue, f.description));
                } else if (f.staticValue instanceof Float) {
                    f.field.set(null, config.getFloat(f.name, f.category, (Float) f.defaultValue, (Float) f.minValue, (Float) f.maxValue, f.description));
                } else if (f.staticValue instanceof Boolean) {
                    f.field.set(null, config.getBoolean(f.name, f.category, (Boolean) f.defaultValue, f.description));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        config.getStringList("chainGroup", Configuration.CATEGORY_GENERAL, chainGroup, "连锁组，连锁时该组内的方块会被视为相同方块进行连锁");
        config.save();
        printConfig();
    }

    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent event) {
        if (event.modID.equalsIgnoreCase(MOD_INFO.MODID)) {
            sync();
        }
    }

    /**
     * 将全局变量写入到配置文件
     */
    public static void globalVarToSave() {
        walkMap(f -> {
            try {
                if (f.staticValue instanceof Integer) {
                    config.get(f.category, f.name, (Integer) f.defaultValue, f.description).set((Integer) f.field.get(null));
                } else if (f.staticValue instanceof Float) {
                    config.get(f.category, f.name, (Float) f.defaultValue, f.description).set((Float) f.field.get(null));
                } else if (f.staticValue instanceof Boolean) {
                    config.get(f.category, f.name, (Boolean) f.defaultValue, f.description).set((Boolean) f.field.get(null));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
        config.save();
        sync();
    }

    public static void printConfig() {
        walkMap(f -> {
            try {
                LOG.info("{}: {}", f.name, f.field.get(null));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static List<ItemStack> getChainGroup() {
        List<ItemStack> list = new ArrayList<>();
        for (String s : chainGroup) {
            JsonElement jsonElement = new JsonParser().parse(s);
            if (jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                String id = jsonObject.get("id").getAsString();
                int meta = 0;
                try {
                    meta = jsonObject.get("meta").getAsInt();
                } catch (Exception ignored) {
                }
                list.add(new ItemStack(Block.getBlockFromName(id), 1, meta));
            }
        }
        return list;
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    public static class ConfigFieldData {
        public Field field;
        public String name;
        public Object staticValue;
        public String category;
        public Object defaultValue;
        public String description;
        public Object minValue;
        public Object maxValue;

        public ConfigFieldData(Field field, String name, Object staticValue, String category,
                               Object defaultValue, String description, Object minValue, Object maxValue) {
            this.field = field;
            this.name = name;
            this.staticValue = staticValue;
            this.category = category;
            this.defaultValue = defaultValue;
            this.description = description;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }
}
