package club.heiqi.qz_miner.log;

import org.apache.logging.log4j.Logger;

/**
 * 日志管理器
 * 提供统一的日志接口，支持不同日志级别
 * 用于跟踪和调试，便于排查问题
 */
public class QzLogManager {
    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("QzMiner");
    
    // 跟踪日志开关，便于调试
    private static boolean traceEnabled = true;
    
    /**
     * 设置跟踪日志开关
     * @param enabled 是否启用跟踪日志
     */
    public static void setTraceEnabled(boolean enabled) {
        traceEnabled = enabled;
    }
    
    /**
     * 获取跟踪日志状态
     * @return 跟踪日志是否启用
     */
    public static boolean isTraceEnabled() {
        return traceEnabled;
    }
    
    /**
     * 记录调试信息
     * @param message 日志消息
     */
    public static void debug(String message) {
        if (traceEnabled) {
            LOGGER.debug(message);
        }
    }
    
    /**
     * 记录调试信息（带格式化参数）
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void debug(String format, Object... args) {
        if (traceEnabled) {
            LOGGER.debug(format, args);
        }
    }
    
    /**
     * 记录普通信息
     * @param message 日志消息
     */
    public static void info(String message) {
        LOGGER.info(message);
    }
    
    /**
     * 记录普通信息（带格式化参数）
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void info(String format, Object... args) {
        LOGGER.info(format, args);
    }
    
    /**
     * 记录警告信息
     * @param message 日志消息
     */
    public static void warn(String message) {
        LOGGER.warn(message);
    }
    
    /**
     * 记录警告信息（带格式化参数）
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void warn(String format, Object... args) {
        LOGGER.warn(format, args);
    }
    
    /**
     * 记录错误信息
     * @param message 日志消息
     */
    public static void error(String message) {
        LOGGER.error(message);
    }
    
    /**
     * 记录错误信息（带异常）
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void error(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
    }
    
    /**
     * 记录错误信息（带格式化参数）
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void error(String format, Object... args) {
        LOGGER.error(format, args);
    }
    
    /**
     * 记录跟踪信息，用于详细调试
     * @param message 日志消息
     */
    public static void trace(String message) {
        if (traceEnabled) {
            LOGGER.trace(message);
        }
    }
    
    /**
     * 记录跟踪信息（带格式化参数）
     * @param format 格式化字符串
     * @param args 参数
     */
    public static void trace(String format, Object... args) {
        if (traceEnabled) {
            LOGGER.trace(format, args);
        }
    }
    
    /**
     * 记录方法进入跟踪
     * @param className 类名
     * @param methodName 方法名
     */
    public static void methodEnter(String className, String methodName) {
        if (traceEnabled) {
            LOGGER.trace("进入方法: {}.{}", className, methodName);
        }
    }
    
    /**
     * 记录方法退出跟踪
     * @param className 类名
     * @param methodName 方法名
     */
    public static void methodExit(String className, String methodName) {
        if (traceEnabled) {
            LOGGER.trace("退出方法: {}.{}", className, methodName);
        }
    }
    
    /**
     * 记录方法异常跟踪
     * @param className 类名
     * @param methodName 方法名
     * @param throwable 异常对象
     */
    public static void methodError(String className, String methodName, Throwable throwable) {
        if (traceEnabled) {
            LOGGER.error("方法异常: {}.{}", className, methodName, throwable);
        }
    }
}