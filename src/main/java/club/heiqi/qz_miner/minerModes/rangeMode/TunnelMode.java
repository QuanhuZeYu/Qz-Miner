package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.breakBlock.BlockBreaker;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Tunnel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.joml.Vector3i;

public class TunnelMode extends AbstractMode {
    @Override
    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        positionFounder = new Tunnel(center, player);
        breaker = new BlockBreaker(player, world);
    }
}
