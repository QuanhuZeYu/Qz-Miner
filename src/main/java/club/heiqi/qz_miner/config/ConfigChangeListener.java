package club.heiqi.qz_miner.config;

/**
 * 配置变更监听接口
 * 当配置发生变化时，会通知所有注册的监听器
 */
public interface ConfigChangeListener {
    
    /**
     * 配置变更事件
     * @param oldConfig 旧配置
     * @param newConfig 新配置
     */
    void onConfigChanged(ConfigData oldConfig, ConfigData newConfig);
    
    /**
     * 配置重置事件
     * @param config 重置后的配置
     */
    void onConfigReset(ConfigData config);
    
    /**
     * 配置加载事件
     * @param config 加载的配置
     */
    void onConfigLoaded(ConfigData config);
    
    /**
     * 配置保存事件
     * @param config 保存的配置
     */
    void onConfigSaved(ConfigData config);
}