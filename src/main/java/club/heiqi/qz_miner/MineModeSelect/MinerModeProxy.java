package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.AllChainMode.RectangularChainMode;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Supplier;

public class MinerModeProxy {
    public static final List<MinerChain> rangeModeSelect = RangeModeEnum.getMinerChain();
    public static final List<String> chainModeSelectString = ChainModeEnum.getUnlocalizedStringList();
    public static final List<String> mainModeSelectString = MainModeEnum.getUnlocalizedStringList();
    public static final List<String> rangeModeListString = RangeModeEnum.getUnlocalizedStringList();

    public static void proxyStart(World world, EntityPlayer player, Point breakPoint) {
        int radius = Config.radiusLimit;
        int blockLimit = Config.blockLimit;
        // 需要检测 主模式 -> 次级模式 -> 选择其供应者
        Statue statue = AllPlayerStatue.getStatue(player.getUniqueID());
        MainModeEnum mainMode = statue.mainMode;
        switch (mainMode) {
            case chainMode -> {
                MinerChain chainMode = statue.chainModeSelect.get(statue.currentChainMode);
//                chainMode.getPoint_supplier(world, player, breakPoint, radius, blockLimit);
                RectangularChainMode.runTask((RectangularChainMode) chainMode, world, player, breakPoint);
            }
            case rangeMode -> {
                MinerChain rangeMode = rangeModeSelect.get(statue.rangeMode.ordinal());
                rangeMode.getPoint_supplier(world, player, breakPoint, radius, blockLimit);
            }
        };
    }
}

