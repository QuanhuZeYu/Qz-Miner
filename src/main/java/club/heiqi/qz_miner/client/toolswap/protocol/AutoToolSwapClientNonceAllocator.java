package club.heiqi.qz_miner.client.toolswap.protocol;

/** 包内测试用的 client nonce 分配契约。 */
interface AutoToolSwapClientNonceAllocator {

    /** @return 新 nonce；返回 0 或负值表示永久耗尽。 */
    long allocate();
}
