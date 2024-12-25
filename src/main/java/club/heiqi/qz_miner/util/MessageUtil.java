package club.heiqi.qz_miner.util;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

public class MessageUtil {
    public static void broadcastMessage(String message) {
        MinecraftServer server = MinecraftServer.getServer();
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            player.addChatMessage(new ChatComponentText(message));
        }
    }
}
