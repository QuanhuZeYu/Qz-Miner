package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

import java.util.List;
import java.util.function.Supplier;

public class MinerModeProxy {
    public static MinerModeProxy INSTANCE;
    public static final List<MinerChain> chainModeSelect = ChainModeEnum.getMinerChain();
    public static final List<MinerChain> rangeModeSelect = RangeModeEnum.getMinerChain();
    public static final List<String> chainModeSelectString = ChainModeEnum.getUnlocalizedStringList();
    public static final List<String> mainModeSelectString = MainModeEnum.getUnlocalizedStringList();
    public static final List<String> rangeModeListString = RangeModeEnum.getUnlocalizedStringList();

    public static void proxyStart(BlockEvent.BreakEvent event, World world, EntityPlayer player, Point breakPoint) {
        int radius = Config.radiusLimit;
        int blockLimit = Config.blockLimit;
        // 需要检测 主模式 -> 次级模式 -> 选择其供应者 -> 获取点 -> 调取破坏方块函数
        Statue statue = AllPlayerStatue.getStatue(player.getUniqueID());
        MainModeEnum mainMode = statue.mainMode;
        Supplier<Point> pointSupplier = switch (mainMode) {
            case chainMode -> {
                MinerChain chainMode = chainModeSelect.get(statue.chainMode.ordinal());
                yield chainMode.getPoint_supplier(world, player, breakPoint, radius, blockLimit);
            }
            case rangeMode -> {
                MinerChain rangeMode = rangeModeSelect.get(statue.rangeMode.ordinal());
                yield rangeMode.getPoint_supplier(world, player, breakPoint, radius, blockLimit);
            }
        };
        Point point;
        while((point = pointSupplier.get()) != null) {
            BlockMethodHelper.tryHarvestBlock(event, world, (EntityPlayerMP) player, point);
        }
    }
}

