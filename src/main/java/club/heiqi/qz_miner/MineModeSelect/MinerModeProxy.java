package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MineModeSelect.AllChainMode.RectangularChainMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.CenterMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.CenterRectangularMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.PlanarRestrictedMode;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MinerModeProxy {
    public static MinerModeProxy INSTANCE;
    public static final ArrayList<MinerChain> chainModeSelect = new ArrayList<>(
        Arrays.asList(new RectangularChainMode())
    );
    public static final ArrayList<MinerChain> rangeModeSelect = new ArrayList<>(
        Arrays.asList(new CenterMode(), new PlanarRestrictedMode(), new CenterRectangularMode())
    );
    public static final List<String> chainModeSelectString = ChainModeEnum.getUnlocalizedStringList();
    public static final List<String> mainModeSelectString = MainModeEnum.getUnlocalizedStringList();
    public static final List<String> rangeModeListString = RangeModeEnum.getUnlocalizedStringList();

    public static Point[] getBlockList(World world, EntityPlayer player, int x, int y, int z) {
        Statue playerStatue = AllPlayerStatue.getStatue(player.getUniqueID());
        return switch (playerStatue.currentMainMode) {
            case 0 -> {
                yield chainModeSelect.get(playerStatue.currentChainMode).getPointList(world, player, x, y, z);
            }
            case 1 -> rangeModeSelect.get(playerStatue.currentRangeMode).getPointList(world, player, x, y, z);
            default -> null;
        };
    }
}

