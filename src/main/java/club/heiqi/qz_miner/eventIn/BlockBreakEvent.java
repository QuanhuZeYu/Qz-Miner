package club.heiqi.qz_miner.eventIn;

import club.heiqi.qz_miner.minerModes.ModeManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import org.joml.Vector3i;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class BlockBreakEvent {
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new BlockBreakEvent());
    }

    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) { // 如果事件在客户端
            return;
        }
        World world = event.world;
        EntityPlayer player = event.getPlayer();
        if (player instanceof FakePlayer) {
            return;
        }
        try {
            if (!allPlayerStorage.playerStatueMap.get(player.getUniqueID()).getIsReady()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        // 获取破坏方块的坐标
        Vector3i breakBlockPos = new Vector3i(event.x, event.y, event.z);
        ModeManager modeManager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        modeManager.proxyMine(world, breakBlockPos, player);
    }
}
