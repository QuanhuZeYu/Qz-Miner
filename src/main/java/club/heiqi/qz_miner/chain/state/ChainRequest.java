package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;

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
    private final int requestedChainRadius;
    private final int requestedChainMaxBlocks;
    private final ModeExtensionSnapshot modeExtension;

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin) {
        this(playerUUID, mode, subMode, origin, 1, 0.0F, 0.0F, 0.0F, -1, -1, null);
    }

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace) {
        this(playerUUID, mode, subMode, origin, interactFace, 0.0F, 0.0F, 0.0F, -1, -1, null);
    }

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ) {
        this(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ, -1, -1, null);
    }

    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ, int requestedChainRadius, int requestedChainMaxBlocks) {
        this(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ,
                requestedChainRadius, requestedChainMaxBlocks, null);
    }

    /** 创建带已选对象组快照的单次请求。 */
    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin,
            int interactFace, float interactHitX, float interactHitY, float interactHitZ,
            int requestedChainRadius, int requestedChainMaxBlocks, ModeExtensionSnapshot modeExtension) {
        this.playerUUID = playerUUID;
        this.mode = mode;
        this.subMode = ChainModeRegistry.resolveSubMode(mode, subMode);
        this.origin = origin;
        this.interactFace = interactFace;
        this.interactHitX = interactHitX;
        this.interactHitY = interactHitY;
        this.interactHitZ = interactHitZ;
        this.requestedChainRadius = requestedChainRadius;
        this.requestedChainMaxBlocks = requestedChainMaxBlocks;
        this.modeExtension = modeExtension == null ? ModeExtensionSnapshot.EMPTY : modeExtension;
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

    public int getRequestedChainRadius() {
        return requestedChainRadius;
    }

    public int getRequestedChainMaxBlocks() {
        return requestedChainMaxBlocks;
    }

    /** @return 本次任务冻结的模式筛选扩展。 */
    public ModeExtensionSnapshot getModeExtension() {
        return modeExtension;
    }
}
