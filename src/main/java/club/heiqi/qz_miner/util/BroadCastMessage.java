package club.heiqi.qz_miner.util;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import java.util.List;

public class BroadCastMessage {

    public static void broadCastMessage(String text) {
        List<EntityPlayerMP> allPlayers = FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList;
        ChatComponentText CCT = new ChatComponentText(text);
        for (EntityPlayerMP playerMP : allPlayers) {
            playerMP.addChatMessage(CCT);
        }
    }

    public static void sendToPlayer(String displayName, String text) {
        List<EntityPlayerMP> allPlayers = FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList;
        ChatComponentText CCT = new ChatComponentText(text);
        for (EntityPlayerMP playerMP : allPlayers) {
            if (playerMP.getDisplayName().equals(displayName)) {
                playerMP.addChatMessage(CCT);
                return;
            }
        }
    }
}
