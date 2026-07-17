package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;

/**
 * 客户端准星目标的不可变值身份。
 *
 * <p>身份只包含方块 id 与 metadata，不包含坐标、世界、方块对象或方块实体。</p>
 */
public final class ToolSwapTargetIdentity {

    public static final ToolSwapTargetIdentity ABSENT = new ToolSwapTargetIdentity(false, 0, 0);

    private final boolean present;
    private final int blockId;
    private final int metadata;

    private ToolSwapTargetIdentity(boolean present, int blockId, int metadata) {
        this.present = present;
        this.blockId = blockId;
        this.metadata = metadata;
    }

    /** 创建有效方块目标身份。 */
    public static ToolSwapTargetIdentity present(int blockId, int metadata) {
        if (blockId <= 0 || blockId > AutoToolSwapProtocol.MAX_BLOCK_ID
                || metadata < 0 || metadata > AutoToolSwapProtocol.MAX_BLOCK_METADATA) {
            throw new IllegalArgumentException("block id/metadata out of range");
        }
        return new ToolSwapTargetIdentity(true, blockId, metadata);
    }

    public boolean isPresent() { return present; }
    public int blockId() { return blockId; }
    public int metadata() { return metadata; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ToolSwapTargetIdentity)) return false;
        ToolSwapTargetIdentity other = (ToolSwapTargetIdentity) value;
        return present == other.present && blockId == other.blockId && metadata == other.metadata;
    }

    @Override
    public int hashCode() {
        int result = present ? 1 : 0;
        result = 31 * result + blockId;
        return 31 * result + metadata;
    }

    @Override
    public String toString() {
        return present ? "PRESENT(" + blockId + "," + metadata + ")" : "ABSENT";
    }
}
