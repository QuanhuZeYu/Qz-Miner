package club.heiqi.qz_miner.toolswap.protocol;

/** 服务端在同一 round 内发出的不可变工具接替请求。 */
public final class AutoToolSwapTakeoverRequest {

    private final int protocolVersion;
    private final long serverRoundId;
    private final long actionSequence;
    private final int generation;
    private final int targetX;
    private final int targetY;
    private final int targetZ;
    private final int targetBlockId;
    private final int targetBlockMetadata;
    private final long serverTick;
    private final long deadlineTick;

    /** 创建并严格校验固定值请求。 */
    public AutoToolSwapTakeoverRequest(int protocolVersion, long serverRoundId, long actionSequence,
            int generation, int targetX, int targetY, int targetZ, int targetBlockId,
            int targetBlockMetadata, long serverTick, long deadlineTick) {
        if (protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION
                || serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || actionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || generation < 0
                || targetY < 0 || targetY > 255 || targetBlockId < 0
                || targetBlockId > AutoToolSwapProtocol.MAX_BLOCK_ID || targetBlockMetadata < 0
                || targetBlockMetadata > AutoToolSwapProtocol.MAX_BLOCK_METADATA || serverTick < 0L
                || deadlineTick <= serverTick) {
            throw new IllegalArgumentException("invalid auto tool takeover request");
        }
        this.protocolVersion = protocolVersion;
        this.serverRoundId = serverRoundId;
        this.actionSequence = actionSequence;
        this.generation = generation;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.targetBlockId = targetBlockId;
        this.targetBlockMetadata = targetBlockMetadata;
        this.serverTick = serverTick;
        this.deadlineTick = deadlineTick;
    }

    public int protocolVersion() { return protocolVersion; }
    public long serverRoundId() { return serverRoundId; }
    public long actionSequence() { return actionSequence; }
    public int generation() { return generation; }
    public int targetX() { return targetX; }
    public int targetY() { return targetY; }
    public int targetZ() { return targetZ; }
    public int targetBlockId() { return targetBlockId; }
    public int targetBlockMetadata() { return targetBlockMetadata; }
    public long serverTick() { return serverTick; }
    public long deadlineTick() { return deadlineTick; }

    /** @return 两个请求是否代表同一等待门。 */
    public boolean sameGate(AutoToolSwapTakeoverRequest other) {
        return other != null && serverRoundId == other.serverRoundId && actionSequence == other.actionSequence
                && generation == other.generation && targetX == other.targetX && targetY == other.targetY
                && targetZ == other.targetZ && targetBlockId == other.targetBlockId
                && targetBlockMetadata == other.targetBlockMetadata;
    }
}
