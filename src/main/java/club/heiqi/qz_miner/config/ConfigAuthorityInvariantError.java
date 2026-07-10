package club.heiqi.qz_miner.config;

/**
 * 配置提交后 Authority 身份或语义不变量破坏时的 fail-stop 错误。
 */
public final class ConfigAuthorityInvariantError extends Error {

    private static final long serialVersionUID = 1L;

    public ConfigAuthorityInvariantError(String message) {
        super(message);
    }

    public ConfigAuthorityInvariantError(String message, Throwable cause) {
        super(message, cause);
    }
}
