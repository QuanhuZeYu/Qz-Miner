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
                || targetY < 0 || targetY > 255 || targetBlockId <= 0
                || targetBlockId > AutoToolSwapProtocol.MAX_BLOCK_ID || targetBlockMetadata < 0
                || targetBlockMetadata > AutoToolSwapProtocol.MAX_BLOCK_METADATA || serverTick < 0L
                || deadlineTick <= serverTick) {
            throw new IllegalArgumentException("invalid auto tool takeover request: targetBlockId="
                    + targetBlockId + " blockIdMax=" + AutoToolSwapProtocol.MAX_BLOCK_ID
                    + " targetBlockMetadata=" + targetBlockMetadata
                    + " metadataMax=" + AutoToolSwapProtocol.MAX_BLOCK_METADATA);
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
    /** @return 为兼容固定 wire 字段名保留的关联值；v4 语义为 takeoverRequestId。 */
    public long actionSequence() { return actionSequence; }
    /** @return 与普通 actionSequence 完全分离的接替请求号。 */
    public long takeoverRequestId() { return actionSequence; }
    public int generation() { return generation; }
    public int targetX() { return targetX; }
    public int targetY() { return targetY; }
    public int targetZ() { return targetZ; }
    public int targetBlockId() { return targetBlockId; }
    public int targetBlockMetadata() { return targetBlockMetadata; }
    public long serverTick() { return serverTick; }
    public long deadlineTick() { return deadlineTick; }

    /** @return 当前执行目标是否仍与请求绑定的 round、代际、坐标和方块事实完全一致。 */
    public boolean matchesTarget(long currentServerRoundId, int currentGeneration,
            int currentTargetX, int currentTargetY, int currentTargetZ,
            int currentTargetBlockId, int currentTargetBlockMetadata) {
        return serverRoundId == currentServerRoundId && generation == currentGeneration
                && targetX == currentTargetX && targetY == currentTargetY && targetZ == currentTargetZ
                && targetBlockId == currentTargetBlockId && targetBlockMetadata == currentTargetBlockMetadata;
    }

    /** @return 两个请求是否代表同一等待门。 */
    public boolean sameGate(AutoToolSwapTakeoverRequest other) {
        return other != null && serverRoundId == other.serverRoundId && actionSequence == other.actionSequence
                && matchesTarget(other.serverRoundId, other.generation, other.targetX, other.targetY,
                        other.targetZ, other.targetBlockId, other.targetBlockMetadata);
    }
}
