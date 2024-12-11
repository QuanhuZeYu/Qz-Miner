package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.breakBlock.BlockBreaker;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Rectangular;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.joml.Vector3i;

public class RectangularMode extends AbstractMode {

    public RectangularMode() {
        super();
    }

    @Override
    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        positionFounder = new Rectangular(center, player);
        breaker = new BlockBreaker(player, world);
    }
}
