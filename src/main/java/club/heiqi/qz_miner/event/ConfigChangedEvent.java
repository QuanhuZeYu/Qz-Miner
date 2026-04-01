package club.heiqi.qz_miner.event;

import club.heiqi.qz_miner.config.ConfigData;

/**
 * 配置变更事件
 * 当配置发生变化时触发此事件
 */
public class ConfigChangedEvent extends BaseEvent {
    private final ConfigData oldConfig;
    private final ConfigData newConfig;
    
    /**
     * 构造函数
     * @param source 事件源
     * @param oldConfig 旧配置
     * @param newConfig 新配置
     */
    public ConfigChangedEvent(Object source, ConfigData oldConfig, ConfigData newConfig) {
        super(source);
        this.oldConfig = oldConfig;
        this.newConfig = newConfig;
    }
    
    /**
     * 获取旧配置
     * @return 旧配置
     */
    public ConfigData getOldConfig() {
        return oldConfig;
    }
    
    /**
     * 获取新配置
     * @return 新配置
     */
    public ConfigData getNewConfig() {
        return newConfig;
    }
    
    @Override
    public String toString() {
        return String.format("ConfigChangedEvent{oldConfig=%s, newConfig=%s}", oldConfig, newConfig);
    }
}