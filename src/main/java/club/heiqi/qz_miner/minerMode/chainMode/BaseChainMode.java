package club.heiqi.qz_miner.minerMode.chainMode;

import club.heiqi.qz_miner.minerMode.enums.Sides;
import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import club.heiqi.qz_miner.minerMode.chainMode.posFounder.ChainFounder;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

public class BaseChainMode extends AbstractMode {
    public Logger LOG = LogManager.getLogger();


    public BaseChainMode(ModeManager modeManager, Vector3i center, Sides sides) {
        super(modeManager, center, sides);
        positionFounder = new ChainFounder(this);
        timer = System.currentTimeMillis();
        addPreUnregisterTask(this::sendMessage);
    }

    /**用于追踪模式运行时间*/
    public long timer;
    public void sendMessage() {
        if (side == Sides.CLIENT) return;
        if (isRenderMode.get() || isInteractMode.get()) return;
        if (isShut) return;
        if (!modeManager.getPrintResult()) return;
        long totalTime = System.currentTimeMillis() - timer;
        // 分割秒和毫秒
        int seconds = (int)(totalTime / 1000);  // 秒数
        long milliseconds = totalTime % 1000;  // 毫秒数
        String message = "本次共挖掘: " + allBreakCount + "个方块"
                + " 共用时: " + seconds + "秒"
                + milliseconds + "毫秒";
        ChatComponentText text = new ChatComponentText(message);
        EntityPlayer player = modeManager.player;
        try {
            player.addChatMessage(text);
        }
        catch (Exception e) {
            LOG.error(e);
        }
    }
}
