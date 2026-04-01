package club.heiqi.qz_miner.event;

import club.heiqi.qz_miner.log.LogManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线
 * 负责事件的分发和监听器管理
 * 支持事件优先级、事件取消机制
 */
public class EventBus {
    private static final String TAG = "EventBus";
    
    // 事件监听器映射：事件类型 -> 监听器列表
    private final Map<Class<? extends BaseEvent>, List<EventListener<?>>> listenerMap = 
        new ConcurrentHashMap<>();
    
    // 监听器注册表：监听器 -> 事件类型列表
    private final Map<EventListener<?>, List<Class<? extends BaseEvent>>> listenerRegistry = 
        new ConcurrentHashMap<>();
    
    // 是否启用事件总线
    private boolean enabled = true;
    
    // 事件统计
    private final Map<Class<? extends BaseEvent>, Long> eventStats = new ConcurrentHashMap<>();
    
    /**
     * 注册事件监听器
     * @param eventType 事件类型
     * @param listener 监听器
     * @param <T> 事件类型
     */
    public <T extends BaseEvent> void register(Class<T> eventType, EventListener<T> listener) {
        if (eventType == null || listener == null) {
            LogManager.error("注册监听器失败：事件类型或监听器不能为空");
            return;
        }
        
        LogManager.methodEnter(TAG, "register");
        LogManager.debug("注册事件监听器: {} -> {}", eventType.getSimpleName(), listener.getName());
        
        // 获取或创建监听器列表
        List<EventListener<?>> listeners = listenerMap.computeIfAbsent(
            eventType, k -> new CopyOnWriteArrayList<>()
        );
        
        // 检查是否已注册
        if (listeners.contains(listener)) {
            LogManager.warn("监听器已注册: {}", listener.getName());
            return;
        }
        
        // 添加监听器并按优先级排序
        listeners.add(listener);
        listeners.sort(Comparator.comparingInt(EventListener::getPriority).reversed());
        
        // 更新注册表
        listenerRegistry.computeIfAbsent(listener, k -> new ArrayList<>()).add(eventType);
        
        LogManager.debug("事件监听器注册完成: {} (优先级: {})", listener.getName(), listener.getPriority());
        LogManager.methodExit(TAG, "register");
    }
    
    /**
     * 注销事件监听器
     * @param eventType 事件类型
     * @param listener 监听器
     * @param <T> 事件类型
     */
    public <T extends BaseEvent> void unregister(Class<T> eventType, EventListener<T> listener) {
        if (eventType == null || listener == null) {
            LogManager.error("注销监听器失败：事件类型或监听器不能为空");
            return;
        }
        
        LogManager.methodEnter(TAG, "unregister");
        LogManager.debug("注销事件监听器: {} -> {}", eventType.getSimpleName(), listener.getName());
        
        List<EventListener<?>> listeners = listenerMap.get(eventType);
        if (listeners != null) {
            listeners.remove(listener);
            
            // 如果列表为空，移除事件类型
            if (listeners.isEmpty()) {
                listenerMap.remove(eventType);
            }
        }
        
        // 更新注册表
        List<Class<? extends BaseEvent>> eventTypes = listenerRegistry.get(listener);
        if (eventTypes != null) {
            eventTypes.remove(eventType);
            if (eventTypes.isEmpty()) {
                listenerRegistry.remove(listener);
            }
        }
        
        LogManager.debug("事件监听器注销完成: {}", listener.getName());
        LogManager.methodExit(TAG, "unregister");
    }
    
    /**
     * 注销监听器的所有事件
     * @param listener 监听器
     */
    public void unregisterAll(EventListener<?> listener) {
        if (listener == null) {
            return;
        }
        
        LogManager.methodEnter(TAG, "unregisterAll");
        LogManager.debug("注销监听器的所有事件: {}", listener.getName());
        
        List<Class<? extends BaseEvent>> eventTypes = listenerRegistry.remove(listener);
        if (eventTypes != null) {
            for (Class<? extends BaseEvent> eventType : eventTypes) {
                List<EventListener<?>> listeners = listenerMap.get(eventType);
                if (listeners != null) {
                    listeners.remove(listener);
                    if (listeners.isEmpty()) {
                        listenerMap.remove(eventType);
                    }
                }
            }
        }
        
        LogManager.debug("监听器所有事件注销完成: {}", listener.getName());
        LogManager.methodExit(TAG, "unregisterAll");
    }
    
    /**
     * 发布事件
     * @param event 事件对象
     * @param <T> 事件类型
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseEvent> void post(T event) {
        if (!enabled || event == null) {
            return;
        }
        
        LogManager.methodEnter(TAG, "post");
        LogManager.trace("发布事件: {}", event);
        
        // 更新事件统计
        eventStats.merge(event.getClass(), 1L, Long::sum);
        
        // 获取事件类型的所有监听器（包括父类）
        List<EventListener<T>> listeners = getListenersForEvent(event);
        
        if (listeners.isEmpty()) {
            LogManager.trace("没有监听器处理事件: {}", event.getEventName());
            return;
        }
        
        // 按优先级顺序执行监听器
        for (EventListener<T> listener : listeners) {
            try {
                // 检查事件是否被取消
                if (event.isCancelled()) {
                    LogManager.trace("事件已被取消，跳过监听器: {}", listener.getName());
                    continue;
                }
                
                LogManager.trace("执行监听器: {} (优先级: {})", listener.getName(), listener.getPriority());
                listener.handleEvent(event);
                
            } catch (Exception e) {
                LogManager.error("监听器处理事件异常: {} -> {}", listener.getName(), event, e);
            }
        }
        
        LogManager.trace("事件处理完成: {}", event.getEventName());
        LogManager.methodExit(TAG, "post");
    }
    
    /**
     * 获取事件的所有监听器（包括父类事件的监听器）
     * @param event 事件对象
     * @param <T> 事件类型
     * @return 监听器列表
     */
    @SuppressWarnings("unchecked")
    private <T extends BaseEvent> List<EventListener<T>> getListenersForEvent(T event) {
        List<EventListener<T>> result = new ArrayList<>();
        Class<?> eventClass = event.getClass();
        
        // 遍历事件类的继承链
        while (eventClass != null && BaseEvent.class.isAssignableFrom(eventClass)) {
            List<EventListener<?>> listeners = listenerMap.get(eventClass);
            if (listeners != null) {
                for (EventListener<?> listener : listeners) {
                    result.add((EventListener<T>) listener);
                }
            }
            
            // 获取父类
            eventClass = eventClass.getSuperclass();
        }
        
        // 按优先级排序
        result.sort(Comparator.comparingInt(EventListener::getPriority).reversed());
        
        return result;
    }
    
    /**
     * 检查是否有监听器处理指定事件类型
     * @param eventType 事件类型
     * @return 是否有监听器
     */
    public boolean hasListeners(Class<? extends BaseEvent> eventType) {
        if (eventType == null) {
            return false;
        }
        
        List<EventListener<?>> listeners = listenerMap.get(eventType);
        return listeners != null && !listeners.isEmpty();
    }
    
    /**
     * 获取事件类型的监听器数量
     * @param eventType 事件类型
     * @return 监听器数量
     */
    public int getListenerCount(Class<? extends BaseEvent> eventType) {
        if (eventType == null) {
            return 0;
        }
        
        List<EventListener<?>> listeners = listenerMap.get(eventType);
        return listeners != null ? listeners.size() : 0;
    }
    
    /**
     * 获取所有已注册的事件类型
     * @return 事件类型集合
     */
    public Set<Class<? extends BaseEvent>> getRegisteredEventTypes() {
        return new HashSet<>(listenerMap.keySet());
    }
    
    /**
     * 获取事件统计信息
     * @return 事件统计映射
     */
    public Map<Class<? extends BaseEvent>, Long> getEventStats() {
        return new HashMap<>(eventStats);
    }
    
    /**
     * 清除事件统计信息
     */
    public void clearEventStats() {
        eventStats.clear();
    }
    
    /**
     * 启用或禁用事件总线
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LogManager.info("事件总线{}", enabled ? "启用" : "禁用");
    }
    
    /**
     * 检查事件总线是否启用
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 清除所有监听器
     */
    public void clear() {
        LogManager.methodEnter(TAG, "clear");
        
        listenerMap.clear();
        listenerRegistry.clear();
        eventStats.clear();
        
        LogManager.info("事件总线已清除所有监听器");
        LogManager.methodExit(TAG, "clear");
    }
    
    /**
     * 获取监听器总数
     * @return 监听器总数
     */
    public int getTotalListenerCount() {
        int count = 0;
        for (List<EventListener<?>> listeners : listenerMap.values()) {
            count += listeners.size();
        }
        return count;
    }
    
    /**
     * 获取事件类型总数
     * @return 事件类型总数
     */
    public int getEventTypeCount() {
        return listenerMap.size();
    }
}