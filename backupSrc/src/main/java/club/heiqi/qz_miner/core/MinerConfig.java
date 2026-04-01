package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.Config;

public class MinerConfig {
    public int bigRadius = Config.bigRadius;
    public int blockLimit = Config.blockLimit;
    public int smallRadius = Config.smallRadius;
    public int tunnelWidth = Config.tunnelWidth;
    public boolean useChainDoneMessage = Config.useChainDoneMessage;

    public MinerConfig() {
    }

    public MinerConfig(int bigRadius, int blockLimit, int smallRadius, int tunnelWidth, boolean useChainDoneMessage) {
        this.bigRadius = bigRadius;
        this.blockLimit = blockLimit;
        this.smallRadius = smallRadius;
        this.tunnelWidth = tunnelWidth;
        this.useChainDoneMessage = useChainDoneMessage;
    }
}
