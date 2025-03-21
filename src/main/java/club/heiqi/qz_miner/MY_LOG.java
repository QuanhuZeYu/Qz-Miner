package club.heiqi.qz_miner;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MY_LOG {
    public static final Logger LOG = LogManager.getLogger(MOD_INFO.MODID);

    public static void printToChatMessage(boolean isRemote, Object... args) {
        if (isRemote) {
            ChatComponentText split = new ChatComponentText("-");
            ChatComponentTranslation translation = new ChatComponentTranslation("");
            for (Object arg : args) {
                ChatComponentTranslation transArg = new ChatComponentTranslation(arg.toString());
                translation.appendSibling(split);
                translation.appendSibling(transArg);
                Minecraft.getMinecraft().thePlayer.addChatMessage(translation);
            }
        }
    }
}
