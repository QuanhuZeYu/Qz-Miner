package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import club.heiqi.qz_miner.Storage.Statue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import static club.heiqi.qz_miner.MY_LOG.logger;

import java.util.List;

public class ProxyMinerMode {
    public static List<AbstractMiner> rangeModeSelect = RangeModeEnum.getMinerChain();
    public static List<AbstractMiner> chainModeSelect = ChainModeEnum.getMinerChain();

    public static void proxyStart(World world, EntityPlayer player, Point breakPoint) {
        Statue pSt = AllPlayerStatue.getStatue(player.getUniqueID());
        if(pSt.isMining) return;  // 一个玩家只允许进行一个挖矿实例任务
        // 需要检测 主模式 -> 次级模式 -> 选择其供应者
        Statue statue = AllPlayerStatue.getStatue(player.getUniqueID());
        MainModeEnum mainMode = statue.mainMode;
        switch (mainMode) {
            case chainMode -> {
                AbstractMiner chainMode = chainModeSelect.get(statue.currentChainMode);
                chainMode.runTask(chainMode, world, player, breakPoint);
            }
            case rangeMode -> {
                AbstractMiner rangeMode = rangeModeSelect.get(statue.currentRangeMode);
//                logger.info("当前模式: {}", RangeModeEnum.getLocalizationList().get(statue.currentRangeMode));
                rangeMode.runTask(rangeMode, world, player, breakPoint);
            }
        }
    }
}

