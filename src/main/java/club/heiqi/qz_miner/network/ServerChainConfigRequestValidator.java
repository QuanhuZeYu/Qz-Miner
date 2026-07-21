package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
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
        return validateAndClamp(requestedRadius, requestedMaxBlocks, serverRadius, serverMaxBlocks,
                PacketChainConfigRequest.LEGACY_PROTOCOL_VERSION,
                TunnelDirectionSource.legacyDefault().wireCode(), true);
    }

    /** v2 整包校验；legacy 精确降级 LOOK。 */
    public static Result validateAndClamp(
            int requestedRadius, int requestedMaxBlocks, int serverRadius, int serverMaxBlocks,
            int protocolVersion, int directionCode, boolean rawValid) {
        if (!rawValid || requestedRadius <= 0 || requestedMaxBlocks <= 0
                || serverRadius <= 0 || serverMaxBlocks <= 0) {
            return Result.rejected();
        }
        TunnelDirectionSource source;
        if (protocolVersion == PacketChainConfigRequest.LEGACY_PROTOCOL_VERSION) {
            source = TunnelDirectionSource.legacyDefault();
        } else if (protocolVersion == PacketChainConfigRequest.PROTOCOL_VERSION) {
            source = TunnelDirectionSource.fromWireCode(directionCode);
        } else {
            source = null;
        }
        if (source == null) {
            return Result.rejected();
        }
        return Result.accepted(
                Math.min(requestedRadius, serverRadius),
                Math.min(requestedMaxBlocks, serverMaxBlocks), source);
    }

    /** 不可变校验结果。 */
    public static final class Result {
        public final boolean accepted;
        public final int radius;
        public final int maxBlocks;
        public final TunnelDirectionSource tunnelDirectionSource;

        private Result(boolean accepted, int radius, int maxBlocks, TunnelDirectionSource source) {
            this.accepted = accepted;
            this.radius = radius;
            this.maxBlocks = maxBlocks;
            this.tunnelDirectionSource = source;
        }

        private static Result rejected() {
            return new Result(false, 0, 0, null);
        }

        private static Result accepted(int radius, int maxBlocks, TunnelDirectionSource source) {
            return new Result(true, radius, maxBlocks, source);
        }
    }
}
