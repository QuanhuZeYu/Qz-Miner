package club.heiqi.qz_miner.client;

/** 判断滚轮事件是否由连锁模式切换手势独占消费。 */
final class WheelChordPolicy {

    private WheelChordPolicy() {
    }

    /**
     * 检查滚轮组合键及客户端运行环境是否完整。
     *
     * @return 命中组合键且允许 Miner 消费事件时为 true
     */
    static boolean shouldConsume(int wheelDelta, boolean chainKeyPressed, boolean noScreen,
            boolean worldAvailable, boolean playerAvailable, boolean serviceAvailable) {
        return wheelDelta != 0
                && chainKeyPressed
                && noScreen
                && worldAvailable
                && playerAvailable
                && serviceAvailable;
    }
}
