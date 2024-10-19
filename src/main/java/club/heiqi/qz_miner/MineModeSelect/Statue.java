package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.network.PacketChangeChainMode;
import club.heiqi.qz_miner.network.PacketChangeMainMode;
import club.heiqi.qz_miner.network.PacketChangeRangeMode;
import club.heiqi.qz_miner.network.Qz_MinerSimpleNetwork;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static club.heiqi.qz_miner.MineModeSelect.MinerModeProxy.*;

public class Statue {
    public boolean minerIsOpen = false;
    public int currentMainMode = 0;

    public int currentChainMode = 0;
    public int currentRangeMode = 0;

    public List<ItemStack> dropsItem = new LinkedList<>();

    public void nextMainMode() {
        currentMainMode = (currentMainMode + 1) % MainModeEnum.values().length;
        MY_LOG.LOG.info("当前主模式为: {}", MainModeEnum.values()[currentMainMode]);
        ChatComponentText prefix = new ChatComponentText("当前主模式为: ");
        ChatComponentTranslation modeText = new ChatComponentTranslation(mainModeSelectString.get(currentMainMode));
        prefix.appendSibling(modeText);
        Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
        Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeMainMode(MainModeEnum.values()[currentMainMode]));


    }

    public void nextMode(InputEvent.KeyInputEvent e) {
        ChatComponentTranslation modeText;
        ChatComponentText prefix;
        ChatComponentText suffix;
        switch (currentMainMode) {
            case 0: // 链模式
                currentChainMode = (currentChainMode + 1) % chainModeSelect.size();
                MY_LOG.LOG.info("当前链模式为: {}", chainModeSelect.get(currentChainMode));
                 modeText = new ChatComponentTranslation(chainModeSelectString.get(currentChainMode));
                 prefix = new ChatComponentText("当前链模式为: ");
                 suffix = new ChatComponentText(".");
                prefix.appendSibling(modeText);
                prefix.appendSibling(suffix);
                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeChainMode(ChainModeEnum.values()[currentChainMode]));
                break;
            case 1: // 爆破模式
                currentRangeMode = (currentRangeMode + 1) % rangeModeSelect.size();
                MY_LOG.LOG.info("当前爆破模式为: {}", rangeModeSelect.get(currentRangeMode));
                 modeText = new ChatComponentTranslation(rangeModeListString.get(currentRangeMode));
                 prefix = new ChatComponentText("当前爆破模式为: ");
                 suffix = new ChatComponentText(".");
                prefix.appendSibling(modeText);
                prefix.appendSibling(suffix);
                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeRangeMode(RangeModeEnum.values()[currentRangeMode]));
        }
        Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeRangeMode(RangeModeEnum.values()[currentRangeMode]));
    }
}
