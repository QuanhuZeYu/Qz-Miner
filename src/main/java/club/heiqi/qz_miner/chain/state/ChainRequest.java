package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 单次连锁请求快照。
 */
public final class ChainRequest {

    private final UUID playerUUID;
    private final ChainMode mode;
    private final ChainSubMode subMode;
    private final ChainTarget origin;
    private final int interactFace;
    private final float interactHitX;
    private final float interactHitY;
    private final float interactHitZ;

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin) {
        this(playerUUID, mode, subMode, origin, 1, 0.0F, 0.0F, 0.0F);
    }

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace) {
        this(playerUUID, mode, subMode, origin, interactFace, 0.0F, 0.0F, 0.0F);
    }

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ) {
        this.playerUUID = playerUUID;
        this.mode = mode;
        this.subMode = ChainModeRegistry.resolveSubMode(mode, subMode);
        this.origin = origin;
        this.interactFace = interactFace;
        this.interactHitX = interactHitX;
        this.interactHitY = interactHitY;
        this.interactHitZ = interactHitZ;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public ChainMode getMode() {
        return mode;
    }

    public ChainSubMode getSubMode() {
        return subMode;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public int getInteractFace() {
        return interactFace;
    }

    public float getInteractHitX() {
        return interactHitX;
    }

    public float getInteractHitY() {
        return interactHitY;
    }

    public float getInteractHitZ() {
        return interactHitZ;
    }
}
