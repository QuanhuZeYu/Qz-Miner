package club.heiqi.qz_miner.client.toolswap;

/** 交给 Minecraft adapter 的无游戏类命令。 */
public final class ToolSwapCommand {

    /** 命令种类。 */
    public enum Type {
        BEGIN_ROUND,
        SEND_SWAP,
        SEND_RESTORE,
        SEND_FREEZE,
        SEND_CLOSE
    }

    public final Type type;
    public final long generation;
    public final int anchorSlot;
    public final int candidateSlot;

    ToolSwapCommand(Type type, long generation, int anchorSlot, int candidateSlot) {
        this.type = type;
        this.generation = generation;
        this.anchorSlot = anchorSlot;
        this.candidateSlot = candidateSlot;
    }
}
