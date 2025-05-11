package club.heiqi.qz_miner.minerMode.rangeMode;

import club.heiqi.qz_miner.minerMode.enums.Sides;
import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import club.heiqi.qz_miner.minerMode.rangeMode.posFounder.RectangularFounder;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import org.joml.Vector3i;

/**
 * 挖掘一切可以挖掘的
 */
public class RectangularMode extends AbstractMode {

    public RectangularMode(ModeManager modeManager, Vector3i center, Sides sides) {
        super(modeManager, center, sides);
        EntityPlayer player = modeManager.player;
        timer = System.currentTimeMillis();
        positionFounder = new RectangularFounder(this);
        addPreUnregisterTask(this::sendMessage);
    }

    public long timer;
    public void sendMessage() {
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
