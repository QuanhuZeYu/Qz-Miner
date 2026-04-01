package club.heiqi.qz_miner.event;

import club.heiqi.qz_miner.log.QzLogManager;

/**
 * 事件管理器
 * 提供全局事件总线访问点，管理事件系统
 */
public class EventManager {
    private static final String TAG = "EventManager";
    
    // 全局事件总线实例
    private static final EventBus EVENT_BUS = new EventBus();
    
    // 是否已初始化
    private static boolean initialized = false;
    
    /**
     * 初始化事件管理器
     */
    public static void init() {
        if (initialized) {
            QzLogManager.warn("事件管理器已经初始化");
            return;
        }
        
        QzLogManager.methodEnter(TAG, "init");
        
        // 注册默认事件监听器（可选）
        registerDefaultListeners();
        
        initialized = true;
        QzLogManager.info("事件管理器初始化完成");
        QzLogManager.methodExit(TAG, "init");
    }
    
    /**
     * 注册默认事件监听器
     */
    private static void registerDefaultListeners() {
        // 这里可以注册一些默认的事件监听器
        // 例如：日志监听器、统计监听器等
        
        // 示例：注册事件日志监听器
        EVENT_BUS.register(BaseEvent.class, new EventListener<BaseEvent>() {
            @Override
            public void handleEvent(BaseEvent event) {
                QzLogManager.trace("事件发生: {}", event);
            }
            
            @Override
            public int getPriority() {
                return -1000; // 低优先级
            }
            
            @Override
            public String getName() {
                return "EventLogListener";
            }
        });
    }
    
    /**
     * 获取全局事件总线
     * @return 事件总线
     */
    public static EventBus getEventBus() {
        return EVENT_BUS;
    }
    
    /**
     * 注册事件监听器
     * @param eventType 事件类型
     * @param listener 监听器
     * @param <T> 事件类型
     */
    public static <T extends BaseEvent> void register(Class<T> eventType, EventListener<T> listener) {
        EVENT_BUS.register(eventType, listener);
    }
    
    /**
     * 注销事件监听器
     * @param eventType 事件类型
     * @param listener 监听器
     * @param <T> 事件类型
     */
    public static <T extends BaseEvent> void unregister(Class<T> eventType, EventListener<T> listener) {
        EVENT_BUS.unregister(eventType, listener);
    }
    
    /**
     * 注销监听器的所有事件
     * @param listener 监听器
     */
    public static void unregisterAll(EventListener<?> listener) {
        EVENT_BUS.unregisterAll(listener);
    }
    
    /**
     * 发布事件
     * @param event 事件对象
     * @param <T> 事件类型
     */
    public static <T extends BaseEvent> void post(T event) {
        EVENT_BUS.post(event);
    }
    
    /**
     * 检查是否有监听器处理指定事件类型
     * @param eventType 事件类型
     * @return 是否有监听器
     */
    public static boolean hasListeners(Class<? extends BaseEvent> eventType) {
        return EVENT_BUS.hasListeners(eventType);
    }
    
    /**
     * 获取事件类型的监听器数量
     * @param eventType 事件类型
     * @return 监听器数量
     */
    public static int getListenerCount(Class<? extends BaseEvent> eventType) {
        return EVENT_BUS.getListenerCount(eventType);
    }
    
    /**
     * 启用或禁用事件总线
     * @param enabled 是否启用
     */
    public static void setEnabled(boolean enabled) {
        EVENT_BUS.setEnabled(enabled);
    }
    
    /**
     * 检查事件总线是否启用
     * @return 是否启用
     */
    public static boolean isEnabled() {
        return EVENT_BUS.isEnabled();
    }
    
    /**
     * 清除所有监听器
     */
    public static void clear() {
        EVENT_BUS.clear();
    }
    
    /**
     * 销毁事件管理器
     */
    public static void destroy() {
        QzLogManager.methodEnter(TAG, "destroy");
        
        clear();
        initialized = false;
        
        QzLogManager.info("事件管理器销毁完成");
        QzLogManager.methodExit(TAG, "destroy");
    }
    
    /**
     * 检查事件管理器是否已初始化
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
}