package club.heiqi.qz_miner.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

public class MessageUtils {
    public static void printSelfMessage(String content) {
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(content));
    }

    public static void sendPlayerMessage(String content, EntityPlayer player) {
        player.addChatMessage(new ChatComponentText(content));
    }
}
