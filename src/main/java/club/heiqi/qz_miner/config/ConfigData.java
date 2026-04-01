package club.heiqi.qz_miner.config;

/**
 * 配置数据类
 * 存储所有配置参数，提供默认值和验证
 */
public class ConfigData {
    // 连锁配置
    private int bigRadius = 8;          // 最大连锁半径
    private int blockLimit = 1024;      // 最大连锁数量
    private int smallRadius = 2;        // 小区域检测半径
    private int tunnelWidth = 1;        // 隧道半径
    
    // 客户端配置
    private boolean usePreview = true;  // 使用预览功能
    private boolean useChainDoneMessage = true; // 使用完成消息
    private double addExhaustion = 0.025; // 每次挖掘增加的饥饿值
    
    // 调试配置
    private boolean enableTraceLog = true; // 启用跟踪日志
    private boolean enableDebugLog = false; // 启用调试日志
    
    /**
     * 获取最大连锁半径
     * @return 最大连锁半径
     */
    public int getBigRadius() {
        return bigRadius;
    }
    
    /**
     * 设置最大连锁半径
     * @param bigRadius 最大连锁半径
     */
    public void setBigRadius(int bigRadius) {
        this.bigRadius = Math.max(0, Math.min(bigRadius, 64)); // 限制在0-64之间
    }
    
    /**
     * 获取最大连锁数量
     * @return 最大连锁数量
     */
    public int getBlockLimit() {
        return blockLimit;
    }
    
    /**
     * 设置最大连锁数量
     * @param blockLimit 最大连锁数量
     */
    public void setBlockLimit(int blockLimit) {
        this.blockLimit = Math.max(0, Math.min(blockLimit, 4096)); // 限制在0-4096之间
    }
    
    /**
     * 获取小区域检测半径
     * @return 小区域检测半径
     */
    public int getSmallRadius() {
        return smallRadius;
    }
    
    /**
     * 设置小区域检测半径
     * @param smallRadius 小区域检测半径
     */
    public void setSmallRadius(int smallRadius) {
        this.smallRadius = Math.max(0, Math.min(smallRadius, 8)); // 限制在0-8之间
    }
    
    /**
     * 获取隧道半径
     * @return 隧道半径
     */
    public int getTunnelWidth() {
        return tunnelWidth;
    }
    
    /**
     * 设置隧道半径
     * @param tunnelWidth 隧道半径
     */
    public void setTunnelWidth(int tunnelWidth) {
        this.tunnelWidth = Math.max(0, Math.min(tunnelWidth, 4)); // 限制在0-4之间
    }
    
    /**
     * 是否使用预览功能
     * @return 是否使用预览功能
     */
    public boolean isUsePreview() {
        return usePreview;
    }
    
    /**
     * 设置是否使用预览功能
     * @param usePreview 是否使用预览功能
     */
    public void setUsePreview(boolean usePreview) {
        this.usePreview = usePreview;
    }
    
    /**
     * 是否使用完成消息
     * @return 是否使用完成消息
     */
    public boolean isUseChainDoneMessage() {
        return useChainDoneMessage;
    }
    
    /**
     * 设置是否使用完成消息
     * @param useChainDoneMessage 是否使用完成消息
     */
    public void setUseChainDoneMessage(boolean useChainDoneMessage) {
        this.useChainDoneMessage = useChainDoneMessage;
    }
    
    /**
     * 获取每次挖掘增加的饥饿值
     * @return 每次挖掘增加的饥饿值
     */
    public double getAddExhaustion() {
        return addExhaustion;
    }
    
    /**
     * 设置每次挖掘增加的饥饿值
     * @param addExhaustion 每次挖掘增加的饥饿值
     */
    public void setAddExhaustion(double addExhaustion) {
        this.addExhaustion = Math.max(0, Math.min(addExhaustion, 1.0)); // 限制在0-1之间
    }
    
    /**
     * 是否启用跟踪日志
     * @return 是否启用跟踪日志
     */
    public boolean isEnableTraceLog() {
        return enableTraceLog;
    }
    
    /**
     * 设置是否启用跟踪日志
     * @param enableTraceLog 是否启用跟踪日志
     */
    public void setEnableTraceLog(boolean enableTraceLog) {
        this.enableTraceLog = enableTraceLog;
    }
    
    /**
     * 是否启用调试日志
     * @return 是否启用调试日志
     */
    public boolean isEnableDebugLog() {
        return enableDebugLog;
    }
    
    /**
     * 设置是否启用调试日志
     * @param enableDebugLog 是否启用调试日志
     */
    public void setEnableDebugLog(boolean enableDebugLog) {
        this.enableDebugLog = enableDebugLog;
    }
    
    /**
     * 验证配置是否有效
     * @return 配置是否有效
     */
    public boolean isValid() {
        return bigRadius >= 0 && bigRadius <= 64 &&
               blockLimit >= 0 && blockLimit <= 4096 &&
               smallRadius >= 0 && smallRadius <= 8 &&
               tunnelWidth >= 0 && tunnelWidth <= 4 &&
               addExhaustion >= 0 && addExhaustion <= 1.0;
    }
    
    /**
     * 重置为默认配置
     */
    public void resetToDefault() {
        bigRadius = 8;
        blockLimit = 1024;
        smallRadius = 2;
        tunnelWidth = 1;
        usePreview = true;
        useChainDoneMessage = true;
        addExhaustion = 0.025;
        enableTraceLog = true;
        enableDebugLog = false;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ConfigData{bigRadius=%d, blockLimit=%d, smallRadius=%d, tunnelWidth=%d, " +
            "usePreview=%b, useChainDoneMessage=%b, addExhaustion=%.3f, " +
            "enableTraceLog=%b, enableDebugLog=%b}",
            bigRadius, blockLimit, smallRadius, tunnelWidth,
            usePreview, useChainDoneMessage, addExhaustion,
            enableTraceLog, enableDebugLog
        );
    }
}