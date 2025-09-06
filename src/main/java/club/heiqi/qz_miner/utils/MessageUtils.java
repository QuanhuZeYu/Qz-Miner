package club.heiqi.qz_miner.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public class MessageUtils {
    public static void printSelfMessage(String content) {
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(content));
    }
}
