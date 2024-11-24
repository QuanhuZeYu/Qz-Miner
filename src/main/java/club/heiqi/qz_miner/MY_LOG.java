package club.heiqi.qz_miner;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MY_LOG {
    public static final Logger logger = LogManager.getLogger(MOD_INFO.MODID);

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

    @SideOnly(Side.CLIENT)
    public static void printMessageClient(String string) {
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(string));
    }
}
