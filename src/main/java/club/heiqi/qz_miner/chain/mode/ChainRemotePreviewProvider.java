package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 子模式远程预览请求提供器。
 */
public interface ChainRemotePreviewProvider {

    /**
     * 发送远程预览请求。
     *
     * @param requestId 请求编号
     * @param target 目标坐标
     * @param radius 预览半径
     * @param maxTargets 预览数量上限
     * @return 是否成功发起请求
     */
    boolean requestPreview(int requestId, ChainTarget target, int radius, int maxTargets);
}
