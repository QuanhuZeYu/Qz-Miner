package club.heiqi.qz_miner.config;

/**
 * 配置验证器接口
 * 用于验证配置值的有效性
 * @param <T> 配置值类型
 */
public interface ConfigValidator<T> {
    /**
     * 验证配置值
     * @param value 配置值
     * @return 验证结果，null表示验证通过，否则返回错误信息
     */
    String validate(T value);
    
    /**
     * 默认验证器，始终通过
     */
    ConfigValidator<Object> DEFAULT = value -> null;
}