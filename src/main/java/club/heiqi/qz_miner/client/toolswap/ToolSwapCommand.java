package club.heiqi.qz_miner.client.toolswap;

/** 交给 Minecraft adapter 的无游戏类命令。 */
public final class ToolSwapCommand {

    /** 命令种类。 */
    public enum Type {
        BEGIN_SWAP,
        BEGIN_RESTORE,
        VERIFY_SLOTS
    }

    public final Type type;
    public final long generation;
    public final int anchorSlot;
    public final int candidateSlot;
    public final Integer transactionId;

    ToolSwapCommand(Type type, long generation, int anchorSlot, int candidateSlot, Integer transactionId) {
        this.type = type;
        this.generation = generation;
        this.anchorSlot = anchorSlot;
        this.candidateSlot = candidateSlot;
        this.transactionId = transactionId;
    }
}
