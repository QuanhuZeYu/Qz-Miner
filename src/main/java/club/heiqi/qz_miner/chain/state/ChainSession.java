package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 单次连锁任务会话。
 */
public class ChainSession {

    private final ChainRequest request;
    private final ChainRuntimeState runtimeState;

    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin) {
        this(playerUUID, mode, subMode, origin, 1, 0.0F, 0.0F, 0.0F, -1, -1);
    }

    /**
     * 创建连锁会话。
     *
     * @param playerUUID 玩家 UUID
     * @param mode 主模式
     * @param subMode 子模式
     * @param origin 起点坐标
     * @param interactFace 交互点击面
     */
    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace) {
        this(playerUUID, mode, subMode, origin, interactFace, 0.0F, 0.0F, 0.0F, -1, -1);
    }

    /**
     * 创建连锁会话。
     *
     * @param playerUUID 玩家 UUID
     * @param mode 主模式
     * @param subMode 子模式
     * @param origin 起点坐标
     * @param interactFace 交互点击面
     * @param interactHitX 命中点 X 偏移
     * @param interactHitY 命中点 Y 偏移
     * @param interactHitZ 命中点 Z 偏移
     */
    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ) {
        this(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ, -1, -1);
    }

    public ChainSession(UUID playerUUID, ChainMode mode, ChainSubMode subMode, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ, int requestedChainRadius, int requestedChainMaxBlocks) {
        this(new ChainRequest(playerUUID, mode, subMode, origin, interactFace, interactHitX, interactHitY, interactHitZ, requestedChainRadius, requestedChainMaxBlocks));
    }

    public ChainSession(ChainRequest request) {
        this.request = request;
        this.runtimeState = new ChainRuntimeState(request == null ? null : request.getPlayerUUID());
    }

    public UUID getPlayerUUID() {
        return request == null ? null : request.getPlayerUUID();
    }

    public ChainMode getMode() {
        return request == null ? null : request.getMode();
    }

    public ChainSubMode getSubMode() {
        return request == null ? null : request.getSubMode();
    }

    public ChainTarget getOrigin() {
        return request == null ? null : request.getOrigin();
    }

    /**
     * 获取触发交互时记录的点击面。
     *
     * @return 点击面编号
     */
    public int getInteractFace() {
        return request == null ? 1 : request.getInteractFace();
    }

    /**
     * 获取交互命中点 X 偏移。
     *
     * @return 命中点 X 偏移
     */
    public float getInteractHitX() {
        return request == null ? 0.0F : request.getInteractHitX();
    }

    /**
     * 获取交互命中点 Y 偏移。
     *
     * @return 命中点 Y 偏移
     */
    public float getInteractHitY() {
        return request == null ? 0.0F : request.getInteractHitY();
    }

    /**
     * 获取交互命中点 Z 偏移。
     *
     * @return 命中点 Z 偏移
     */
    public float getInteractHitZ() {
        return request == null ? 0.0F : request.getInteractHitZ();
    }

    public ChainRequest getRequest() {
        return request;
    }

    public ChainRuntimeState getRuntimeState() {
        return runtimeState;
    }

    public void clearRuntimeState(String reason) {
        runtimeState.clear(reason);
    }
}
