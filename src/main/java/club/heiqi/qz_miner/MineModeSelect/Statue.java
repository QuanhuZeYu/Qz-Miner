package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MineModeSelect.AllChainMode.RectangularChainMode;
import club.heiqi.qz_miner.network.PacketChangeChainMode;
import club.heiqi.qz_miner.network.PacketChangeMainMode;
import club.heiqi.qz_miner.network.PacketChangeRangeMode;
import club.heiqi.qz_miner.network.Qz_MinerSimpleNetwork;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Statue {
    public boolean minerIsOpen = false;
    public int currentMainMode = 0;

    public int currentChainMode = 0;
    public int currentRangeMode = 0;

    public MainModeEnum mainMode = MainModeEnum.chainMode;  // 默认为连锁模式
    public ChainModeEnum chainMode = ChainModeEnum.rectangularMode;
    public RangeModeEnum rangeMode = RangeModeEnum.centerMode;

    public List<AbstractMiner> chainModeSelect = new ArrayList<>(Arrays.asList(new RectangularChainMode()));

    public List<ItemStack> dropsItem = new ArrayList<>();

    public void nextMainMode() {
        currentMainMode = (currentMainMode + 1) % MainModeEnum.values().length;
        mainMode = MainModeEnum.nextMode(mainMode);

        Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeMainMode(mainMode));
        sendStatue();
    }

    public void nextMode(InputEvent.KeyInputEvent e) {
//        switch (currentMainMode) {
//            case 0: // 链模式
//                currentChainMode = (currentChainMode + 1) % chainModeSelect.size();
//                chainMode = ChainModeEnum.nextMode(chainMode);
//
//                MY_LOG.LOG.info("当前链模式为: {}", chainModeSelect.get(currentChainMode));
//                 modeText = new ChatComponentTranslation(chainModeSelectString.get(currentChainMode));
//                 prefix = new ChatComponentText("当前链模式为: ");
//                 suffix = new ChatComponentText(".");
//                prefix.appendSibling(modeText);
//                prefix.appendSibling(suffix);
//                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
//                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeChainMode(chainMode));
//                break;
//            case 1: // 爆破模式
//                currentRangeMode = (currentRangeMode + 1) % rangeModeSelect.size();
//                rangeMode = RangeModeEnum.nextMode(rangeMode);
//
//                MY_LOG.LOG.info("当前爆破模式为: {}", rangeModeSelect.get(currentRangeMode));
//                 modeText = new ChatComponentTranslation(rangeModeListString.get(currentRangeMode));
//                 prefix = new ChatComponentText("当前爆破模式为: ");
//                 suffix = new ChatComponentText(".");
//                prefix.appendSibling(modeText);
//                prefix.appendSibling(suffix);
//                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
//                Minecraft.getMinecraft().thePlayer.addChatMessage(prefix);
//                Qz_MinerSimpleNetwork.sendMessageToServer(new PacketChangeRangeMode(rangeMode));
//        }

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
