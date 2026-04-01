package club.heiqi.qz_miner.config;

import club.heiqi.qz_miner.event.ConfigChangedEvent;
import club.heiqi.qz_miner.event.EventManager;
import club.heiqi.qz_miner.log.LogManager;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置管理器
 * 负责配置的加载、保存、更新和监听
 * 支持Forge配置系统，提供配置变更通知
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    
    // 配置文件
    private Configuration config;
    private File configFile;
    
    // 当前配置数据
    private ConfigData currentConfig = new ConfigData();
    
    // 配置变更监听器
    private List<ConfigChangeListener> listeners = new ArrayList<>();
    
    // 是否已初始化
    private boolean initialized = false;
    
    /**
     * 初始化配置管理器
     * @param configFile 配置文件
     */
    public void init(File configFile) {
        LogManager.methodEnter(TAG, "init");
        
        if (initialized) {
            LogManager.warn("配置管理器已经初始化");
            return;
        }
        
        this.configFile = configFile;
        this.config = new Configuration(configFile);
        
        // 加载配置
        loadConfig();
        
        initialized = true;
        LogManager.info("配置管理器初始化完成，配置文件: {}", configFile.getAbsolutePath());
        LogManager.methodExit(TAG, "init");
    }
    
    /**
     * 加载配置
     */
    public void loadConfig() {
        LogManager.methodEnter(TAG, "loadConfig");
        
        if (config == null) {
            LogManager.error("配置文件未初始化");
            return;
        }
        
        try {
            // 加载配置文件
            config.load();
            
            // 读取配置值
            ConfigData oldConfig = currentConfig;
            currentConfig = new ConfigData();
            
            // 连锁配置
            currentConfig.setBigRadius(config.getInt(
                "bigRadius", Configuration.CATEGORY_GENERAL, 
                8, 0, 64, "最大连锁半径"
            ));
            
            currentConfig.setBlockLimit(config.getInt(
                "blockLimit", Configuration.CATEGORY_GENERAL,
                1024, 0, 4096, "最大连锁数量"
            ));
            
            currentConfig.setSmallRadius(config.getInt(
                "smallRadius", Configuration.CATEGORY_GENERAL,
                2, 0, 8, "连锁小区域检测半径"
            ));
            
            currentConfig.setTunnelWidth(config.getInt(
                "tunnelWidth", Configuration.CATEGORY_GENERAL,
                1, 0, 4, "隧道半径"
            ));
            
            // 客户端配置
            currentConfig.setUsePreview(config.getBoolean(
                "usePreview", "Client",
                true, "是否使用连锁预览功能"
            ));
            
            currentConfig.setUseChainDoneMessage(config.getBoolean(
                "useChainDoneMessage", "Client",
                true, "是否使用连锁后的消息提示"
            ));
            
            currentConfig.setAddExhaustion(config.get(
                "Client", "addExhaustion", 0.025,
                "每次挖掘增加的饥饿值", 0.0, 1.0
            ).getDouble());
            
            // 调试配置
            currentConfig.setEnableTraceLog(config.getBoolean(
                "enableTraceLog", "Debug",
                true, "启用跟踪日志"
            ));
            
            currentConfig.setEnableDebugLog(config.getBoolean(
                "enableDebugLog", "Debug",
                false, "启用调试日志"
            ));
            
            // 保存配置（如果有更改）
            if (config.hasChanged()) {
                config.save();
            }
            
            // 通知监听器配置加载完成
            notifyConfigLoaded(currentConfig);
            
            LogManager.info("配置加载完成: {}", currentConfig);
            LogManager.methodExit(TAG, "loadConfig");
            
        } catch (Exception e) {
            LogManager.error("加载配置文件失败", e);
            // 使用默认配置
            currentConfig.resetToDefault();
        }
    }
    
    /**
     * 保存配置
     */
    public void saveConfig() {
        LogManager.methodEnter(TAG, "saveConfig");
        
        if (config == null) {
            LogManager.error("配置文件未初始化");
            return;
        }
        
        try {
            // 更新配置值
            Property bigRadiusProp = config.get(Configuration.CATEGORY_GENERAL, "bigRadius", 8);
            bigRadiusProp.set(currentConfig.getBigRadius());
            
            Property blockLimitProp = config.get(Configuration.CATEGORY_GENERAL, "blockLimit", 1024);
            blockLimitProp.set(currentConfig.getBlockLimit());
            
            Property smallRadiusProp = config.get(Configuration.CATEGORY_GENERAL, "smallRadius", 2);
            smallRadiusProp.set(currentConfig.getSmallRadius());
            
            Property tunnelWidthProp = config.get(Configuration.CATEGORY_GENERAL, "tunnelWidth", 1);
            tunnelWidthProp.set(currentConfig.getTunnelWidth());
            
            Property usePreviewProp = config.get("Client", "usePreview", true);
            usePreviewProp.set(currentConfig.isUsePreview());
            
            Property useChainDoneMessageProp = config.get("Client", "useChainDoneMessage", true);
            useChainDoneMessageProp.set(currentConfig.isUseChainDoneMessage());
            
            Property addExhaustionProp = config.get("Client", "addExhaustion", 0.025);
            addExhaustionProp.set(currentConfig.getAddExhaustion());
            
            Property enableTraceLogProp = config.get("Debug", "enableTraceLog", true);
            enableTraceLogProp.set(currentConfig.isEnableTraceLog());
            
            Property enableDebugLogProp = config.get("Debug", "enableDebugLog", false);
            enableDebugLogProp.set(currentConfig.isEnableDebugLog());
            
            // 保存配置文件
            config.save();
            
            // 通知监听器配置保存完成
            notifyConfigSaved(currentConfig);
            
            LogManager.info("配置保存完成");
            LogManager.methodExit(TAG, "saveConfig");
            
        } catch (Exception e) {
            LogManager.error("保存配置文件失败", e);
        }
    }
    
    /**
     * 更新配置
     * @param newConfig 新配置
     */
    public void updateConfig(ConfigData newConfig) {
        LogManager.methodEnter(TAG, "updateConfig");
        
        if (newConfig == null) {
            LogManager.error("新配置不能为空");
            return;
        }
        
        if (!newConfig.isValid()) {
            LogManager.error("配置验证失败: {}", newConfig);
            return;
        }
        
        ConfigData oldConfig = currentConfig;
        currentConfig = newConfig;
        
        // 保存配置
        saveConfig();
        
        // 通知监听器配置变更
        notifyConfigChanged(oldConfig, currentConfig);
        
        // 发布配置变更事件
        ConfigChangedEvent event = new ConfigChangedEvent(this, oldConfig, currentConfig);
        EventManager.post(event);
        
        LogManager.info("配置更新完成: {}", currentConfig);
        LogManager.methodExit(TAG, "updateConfig");
    }
    
    /**
     * 重置配置为默认值
     */
    public void resetConfig() {
        LogManager.methodEnter(TAG, "resetConfig");
        
        ConfigData oldConfig = currentConfig;
        currentConfig = new ConfigData();
        
        // 保存配置
        saveConfig();
        
        // 通知监听器配置重置
        notifyConfigReset(currentConfig);
        
        // 发布配置变更事件
        ConfigChangedEvent event = new ConfigChangedEvent(this, oldConfig, currentConfig);
        EventManager.post(event);
        
        LogManager.info("配置重置为默认值: {}", currentConfig);
        LogManager.methodExit(TAG, "resetConfig");
    }
    
    /**
     * 获取当前配置
     * @return 当前配置
     */
    public ConfigData getCurrentConfig() {
        return currentConfig;
    }
    
    /**
     * 获取配置文件
     * @return 配置文件
     */
    public File getConfigFile() {
        return configFile;
    }
    
    /**
     * 添加配置变更监听器
     * @param listener 监听器
     */
    public void addConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            LogManager.debug("添加配置变更监听器: {}", listener.getClass().getName());
        }
    }
    
    /**
     * 移除配置变更监听器
     * @param listener 监听器
     */
    public void removeConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            LogManager.debug("移除配置变更监听器: {}", listener.getClass().getName());
        }
    }
    
    /**
     * 通知配置变更
     * @param oldConfig 旧配置
     * @param newConfig 新配置
     */
    private void notifyConfigChanged(ConfigData oldConfig, ConfigData newConfig) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(oldConfig, newConfig);
            } catch (Exception e) {
                LogManager.error("通知配置变更监听器失败: {}", listener.getClass().getName(), e);
            }
        }
    }
    
    /**
     * 通知配置重置
     * @param config 重置后的配置
     */
    private void notifyConfigReset(ConfigData config) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigReset(config);
            } catch (Exception e) {
                LogManager.error("通知配置重置监听器失败: {}", listener.getClass().getName(), e);
            }
        }
    }
    
    /**
     * 通知配置加载
     * @param config 加载的配置
     */
    private void notifyConfigLoaded(ConfigData config) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigLoaded(config);
            } catch (Exception e) {
                LogManager.error("通知配置加载监听器失败: {}", listener.getClass().getName(), e);
            }
        }
    }
    
    /**
     * 通知配置保存
     * @param config 保存的配置
     */
    private void notifyConfigSaved(ConfigData config) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigSaved(config);
            } catch (Exception e) {
                LogManager.error("通知配置保存监听器失败: {}", listener.getClass().getName(), e);
            }
        }
    }
    
    /**
     * 检查配置是否已初始化
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 销毁配置管理器
     */
    public void destroy() {
        LogManager.methodEnter(TAG, "destroy");
        
        if (config != null) {
            // 保存配置
            if (config.hasChanged()) {
                config.save();
            }
        }
        
        listeners.clear();
        initialized = false;
        
        LogManager.info("配置管理器销毁完成");
        LogManager.methodExit(TAG, "destroy");
    }
}