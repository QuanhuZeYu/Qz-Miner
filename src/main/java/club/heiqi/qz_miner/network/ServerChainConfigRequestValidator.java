package club.heiqi.qz_miner.network;

/**
 * C2S 连锁配置请求的服务端整包校验与上限投影。
 */
public final class ServerChainConfigRequestValidator {

    private ServerChainConfigRequestValidator() {
    }

    /**
     * 先整包拒绝非正请求，再把两个合法字段整体夹到服务端上限。
     *
     * @param requestedRadius 客户端原始半径
     * @param requestedMaxBlocks 客户端原始目标上限
     * @param serverRadius 服务端半径上限
     * @param serverMaxBlocks 服务端目标上限
     * @return 校验结果
     */
    public static Result validateAndClamp(
            int requestedRadius,
            int requestedMaxBlocks,
            int serverRadius,
            int serverMaxBlocks) {
        if (requestedRadius <= 0 || requestedMaxBlocks <= 0 || serverRadius <= 0 || serverMaxBlocks <= 0) {
            return Result.rejected();
        }
        return Result.accepted(
                Math.min(requestedRadius, serverRadius),
                Math.min(requestedMaxBlocks, serverMaxBlocks));
    }

    /** 不可变校验结果。 */
    public static final class Result {
        public final boolean accepted;
        public final int radius;
        public final int maxBlocks;

        private Result(boolean accepted, int radius, int maxBlocks) {
            this.accepted = accepted;
            this.radius = radius;
            this.maxBlocks = maxBlocks;
        }

        private static Result rejected() {
            return new Result(false, 0, 0);
        }

        private static Result accepted(int radius, int maxBlocks) {
            return new Result(true, radius, maxBlocks);
        }
    }
}
