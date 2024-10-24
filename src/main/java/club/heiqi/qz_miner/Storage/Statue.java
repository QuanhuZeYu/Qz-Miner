package club.heiqi.qz_miner.Storage;

import club.heiqi.qz_miner.MineModeSelect.ChainModeEnum;
import club.heiqi.qz_miner.MineModeSelect.MainModeEnum;
import club.heiqi.qz_miner.MineModeSelect.RangeModeEnum;
import club.heiqi.qz_miner.network.PacketChangeChainMode;
import club.heiqi.qz_miner.network.PacketChangeMainMode;
import club.heiqi.qz_miner.network.PacketChangeRangeMode;
import club.heiqi.qz_miner.network.Qz_MinerSimpleNetwork;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import java.util.ArrayList;
import java.util.List;

public class Statue {
    public boolean minerIsOpen = false;
    public boolean isMining = false;
    public int currentMainMode = 0;

    public int currentChainMode = 0;
    public int currentRangeMode = 0;

    public MainModeEnum mainMode = MainModeEnum.chainMode;  // 默认为连锁模式
    public ChainModeEnum chainMode = ChainModeEnum.rectangularMode;
    public RangeModeEnum rangeMode = RangeModeEnum.centerMode;


    public List<ItemStack> dropsItem = new ArrayList<>();

    public void nextMainMode() {
        currentMainMode = (currentMainMode + 1) % MainModeEnum.values().length;
        mainMode = MainModeEnum.nextMode(mainMode);

        Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeMainMode(mainMode));
        sendStatue();
    }

    public void nextMode(InputEvent.KeyInputEvent e) {
        switch(mainMode) {
            case chainMode -> {
                chainMode = ChainModeEnum.nextMode(chainMode);
                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeChainMode(chainMode));
            }
            case rangeMode -> {
                rangeMode = RangeModeEnum.nextMode(rangeMode);
                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeRangeMode(rangeMode));
            }
        }
        sendStatue();
    }

    public void sendStatue() {
        ChatComponentTranslation mainModeText;
        ChatComponentTranslation witchModeText;
        ChatComponentTranslation text;
        ChatComponentTranslation splitText = new ChatComponentTranslation(" - ");
        switch(mainMode) {
            case chainMode -> {
                mainModeText = new ChatComponentTranslation(MainModeEnum.getUnlocalizedStringList().get(mainMode.ordinal()));
                witchModeText = new ChatComponentTranslation(ChainModeEnum.getUnlocalizedStringList().get(chainMode.ordinal()));
                text = new ChatComponentTranslation("当前模式为: ");
                text.appendSibling(mainModeText);
                text.appendSibling(splitText);
                text.appendSibling(witchModeText);
                Minecraft.getMinecraft().thePlayer.addChatMessage(text);
            }
            case rangeMode -> {
                mainModeText = new ChatComponentTranslation(MainModeEnum.getUnlocalizedStringList().get(mainMode.ordinal()));
                witchModeText = new ChatComponentTranslation(RangeModeEnum.getUnlocalizedStringList().get(rangeMode.ordinal()));
                text = new ChatComponentTranslation("当前模式为: ");
                text.appendSibling(mainModeText);
                text.appendSibling(splitText);
                text.appendSibling(witchModeText);
                Minecraft.getMinecraft().thePlayer.addChatMessage(text);
            }
        }
    }
}
