package club.heiqi.qz_miner.util;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class GlobalGet {

    @Nullable
    public static EntityPlayerMP getPlayerByUUID(UUID uuid) {
        List<EntityPlayerMP> playerList = FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList;
        for (EntityPlayerMP player : playerList) {
            if (player.getUniqueID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }
}
