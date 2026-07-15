package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;

/** 客户端自动工具换位 C2S 传输边界，不向 controller 暴露 Forge packet 类型。 */
public interface AutoToolSwapClientTransport {

    /**
     * 提交新的服务端 round 建立请求。
     *
     * @param clientNonce 客户端分配的 nonce
     * @return 请求已交给传输层时为 true
     */
    boolean sendRoundStart(long clientNonce);

    /**
     * 提交一个不可变动作意图。
     *
     * @param intent 服务端协议意图
     * @return 请求已交给传输层时为 true
     */
    boolean sendIntent(AutoToolSwapIntent intent);
}
