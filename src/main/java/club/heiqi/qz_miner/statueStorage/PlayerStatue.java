package club.heiqi.qz_miner.statueStorage;

import club.heiqi.qz_miner.minerModes.ModeManager;
import net.minecraft.entity.player.EntityPlayerMP;

public class PlayerStatue {
    public ModeManager modeManager;

    public PlayerStatue(EntityPlayerMP player) {
        modeManager = new ModeManager(player);
    }

    @Override
    public int hashCode() {
        return modeManager.player.hashCode();
    }
}
