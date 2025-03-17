package club.heiqi.qz_miner;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
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
    public static int taskTimeLimit = 16;
    public static int neighborDistance = 4;
    public static int heartbeatTimeout = 5_000; // 5s
    public static float exhaustion = 1f;
    public static float overMiningDamage = 0.0003f;
    public static int maxFortuneLevel = 3;
    public static float dropDistance = 1.1f;
    public static float coolDown = 30.0f;
    public static boolean forceNatural = false;
    public static boolean dropItemToSelf = true;
    public static boolean unknownDropToPlayer = true;

    public static final String CATEGORY_CLIENT = "client";
    public static float renderLineWidth = 1.5f;
    public static float renderFadeSpeedMultiplier = 1000.0f;
    public static float renderTime = 8f;
    public static float renderDistance = 4.5f;
    public static boolean cullRender = false;
    public static boolean printResult = true;
    public static boolean showTip = true;
    public static boolean useRender = true;

    public static class ConfigEntry<T> {
        public final T field;
        public final String category;
        public final T defaultValue;
        public final String desc;
        public T minValue;
        public T maxValue;

        public ConfigEntry(T fieldName, String category, T defaultValue, String desc, T minValue, T maxValue) {
            this.field = fieldName;
            this.category = category;
            this.defaultValue = defaultValue;
            this.desc = desc;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }

    public static Map<String, ConfigEntry<?>> entryMap = new HashMap<>();
    static {
        // 用于传递进去判断类型                                                     // 分类
        entryMap.put("radiusLimit", new ConfigEntry<>(radiusLimit, Configuration.CATEGORY_GENERAL, 10, "最大搜索范围", 1, Integer.MAX_VALUE));
        entryMap.put("blockLimit", new ConfigEntry<>(blockLimit, Configuration.CATEGORY_GENERAL, 1024, "连锁上限", 1, Integer.MAX_VALUE));
        entryMap.put("perTickBlockLimit", new ConfigEntry<>(perTickBlockLimit, Configuration.CATEGORY_GENERAL, 64, "每tick挖掘限制数量", 1, Integer.MAX_VALUE));
        entryMap.put("taskTimeLimit", new ConfigEntry<>(taskTimeLimit, Configuration.CATEGORY_GENERAL, 10, "每tick(50ms)内任务运行执行的时间 /ms", 10, Integer.MAX_VALUE));
        entryMap.put("neighborDistance", new ConfigEntry<>(neighborDistance, Configuration.CATEGORY_GENERAL, 3, "连锁邻居探测距离", 1, Integer.MAX_VALUE));
        entryMap.put("heartbeatTimeout", new ConfigEntry<>(heartbeatTimeout, Configuration.CATEGORY_GENERAL, 1_000, "任务心跳超时时间", 100, Integer.MAX_VALUE));
        entryMap.put("exhaustion", new ConfigEntry<>(exhaustion, Configuration.CATEGORY_GENERAL, 0.25f, "每次挖掘消耗饱食度", 0f, Float.MAX_VALUE));
        entryMap.put("overMiningDamage", new ConfigEntry<>(overMiningDamage, Configuration.CATEGORY_GENERAL, 0.0001f, "饱食度过低时每次挖掘消耗的生命值", 0f, Float.MAX_VALUE));
        entryMap.put("maxFortuneLevel", new ConfigEntry<>(maxFortuneLevel, Configuration.CATEGORY_GENERAL, 3 , "矿石接受的最大时运等级", 3, 255));
        entryMap.put("dropDistance", new ConfigEntry<>(dropDistance, Configuration.CATEGORY_GENERAL, 1.1f, "挖掘时掉落物距离自身的距离，值越大掉落物越远", 0f, Float.MAX_VALUE));
        entryMap.put("coolDown", new ConfigEntry<>(coolDown, Configuration.CATEGORY_GENERAL, 30.f, "每次揭示时需要等待的时间，单位秒", 0f, Float.MAX_VALUE));
        entryMap.put("forceNatural", new ConfigEntry<>(forceNatural, Configuration.CATEGORY_GENERAL, false, "强制所有矿石为时运", null, null));
        entryMap.put("dropItemToSelf", new ConfigEntry<>(dropItemToSelf, Configuration.CATEGORY_GENERAL, true, "是否将掉落物生成在脚下", null, null));
        entryMap.put("unknownDropToPlayer", new ConfigEntry<>(unknownDropToPlayer, Configuration.CATEGORY_GENERAL, true, "非连锁物也生成在玩家附近", null, null));

        entryMap.put("renderLineWidth", new ConfigEntry<>(renderLineWidth, CATEGORY_CLIENT, 1.5f, "渲染线框宽度", 0.1f, 100f));
        entryMap.put("renderFadeSpeedMultiplier", new ConfigEntry<>(renderFadeSpeedMultiplier, CATEGORY_CLIENT, 50.f, "渲染框颜色变化时间乘数，越大越慢", 0.f, Float.MAX_VALUE));
        entryMap.put("renderTime", new ConfigEntry<>(renderTime, CATEGORY_CLIENT, 8f, "每帧渲染选择结果允许的时长，过短可能会导致闪烁或者无法显示", -1f, Float.MAX_VALUE));
        entryMap.put("renderDistance", new ConfigEntry<>(renderDistance, CATEGORY_CLIENT, 4.5f, "选择渲染框选择点最大距离", 3f, Float.MAX_VALUE));
        entryMap.put("printResult", new ConfigEntry<>(printResult, CATEGORY_CLIENT, true, "在聊天栏打印挖掘结果", null, null));
        entryMap.put("cullRender", new ConfigEntry<>(cullRender, CATEGORY_CLIENT, false, "是否剔除重合边", null, null));
        entryMap.put("showTip", new ConfigEntry<>(showTip, CATEGORY_CLIENT, true, "是否显示左下角提示", null, null));
        entryMap.put("useRender", new ConfigEntry<>(useRender, CATEGORY_CLIENT, true, "是否使用渲染功能", null, null));
    }

    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
            sync();
        }
    }

//    public static void walkMap(Consumer<ConfigFieldData> handler) {
//        Iterator<String> iterator = configMap.keySet().iterator();
//        Class<Config> clazz = Config.class;
//        while (iterator.hasNext()) {
//            String name = iterator.next();
//            Collection<Object> value = configMap.get(name);
//            List<Object> values = new ArrayList<>(value);
//            Object staticValue = values.get(0); // 用于获取类型
//            Field field = null;
//            try {
//                field = clazz.getDeclaredField(name);
//            } catch (NoSuchFieldException e) {
//                throw new RuntimeException(e);
//            }
//            String category = (String) values.get(1);
//            Object defaultValue = values.get(2);
//            String description = (String) values.get(3);
//            Object minValue = new Object(), maxValue = new Object();
//            if (values.size() > 4) {
//                minValue = values.get(4);
//                maxValue = values.get(5);
//            }
//            // 在此处可以插入自定义的函数处理上述内容
//            ConfigFieldData fieldData = new ConfigFieldData(field, name, staticValue, category, defaultValue, description, minValue, maxValue);
//            handler.accept(fieldData); // 回调
//        }
//    }

    public static void walkMap(Consumer<ConfigFieldData> handler) {
        Iterator<String> iterator = entryMap.keySet().iterator();
        Class<Config> clazz = Config.class;
        while (iterator.hasNext()) {
            String name = iterator.next();
            ConfigEntry<?> value = entryMap.get(name);
            Object staticValue = value.field; // 用于获取类型
            Field field;
            try {
                field = clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
            String category = value.category;
            Object defaultValue = value.defaultValue;
            String description = value.desc;
            Object minValue = new Object(), maxValue = new Object();
            if (value.minValue != null) minValue = value.minValue;
            if (value.maxValue != null) maxValue = value.maxValue;
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
