package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import net.minecraft.block.Block;

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
    private final Block seedBlock;
    private final int seedMeta;
    private final TileIdentityToken seedTileIdentity;

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
        this(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ,
                requestedChainRadius, requestedChainMaxBlocks, modeExtension,
                null, 0, TileIdentityToken.unresolved());
    }

    /**
     * 创建同时携带触发时交互 seed 的单次请求。
     *
     * @param seedBlock 触发窗口冻结的方块，可为 null
     * @param seedMeta 完整非负 metadata
     * @param seedTileIdentity 不持有 live TileEntity 的纯值身份
     */
    public ChainRequest(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin,
            int interactFace, float interactHitX, float interactHitY, float interactHitZ,
            int requestedChainRadius, int requestedChainMaxBlocks, ModeExtensionSnapshot modeExtension,
            Block seedBlock, int seedMeta, TileIdentityToken seedTileIdentity) {
        if (seedMeta < 0) {
            throw new IllegalArgumentException("seedMeta must be non-negative");
        }
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
        this.seedBlock = seedBlock;
        this.seedMeta = seedMeta;
        this.seedTileIdentity = seedTileIdentity == null
                ? TileIdentityToken.unresolved()
                : seedTileIdentity;
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

    /** @return 触发窗口冻结的 seed 方块，可为 null */
    public Block getSeedBlock() {
        return seedBlock;
    }

    /** @return 未截断的非负 seed metadata */
    public int getSeedMeta() {
        return seedMeta;
    }

    /** @return 非 null 的 seed TileEntity 纯值身份 */
    public TileIdentityToken getSeedTileIdentity() {
        return seedTileIdentity;
    }
}
