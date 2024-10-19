package club.heiqi.qz_miner.Core;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.MinerModeProxy;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

public class CoreLogic_BlockBreaker {
    public World world = null;
    public EntityPlayer player = null;

    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent breakEvent) {
        if(breakEvent.world.isRemote) {
            MY_LOG.LOG.warn("客户端下阻止使用");
            return;
        }
        if(!AllPlayerStatue.getStatue(breakEvent.getPlayer().getUniqueID()).minerIsOpen) {
            return;
        }
        // 获取破坏方块的坐标
        int x = breakEvent.x;
        int y = breakEvent.y;
        int z = breakEvent.z;

        this.world = breakEvent.world;
        this.player = breakEvent.getPlayer();

        MinerModeProxy.proxyStart(breakEvent, world, player, new Point(x, y, z));

//        Point[] blockList = MinerModeProxy.getBlockList(this.world, player, x, y, z);
//        DebugPrint.printBlockList(world, blockList); // 打印调试信息
//        breakBlock(breakEvent, player, x, y, z);
    }
}
