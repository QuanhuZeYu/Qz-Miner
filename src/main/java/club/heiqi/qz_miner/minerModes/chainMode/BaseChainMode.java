package club.heiqi.qz_miner.minerModes.chainMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.util.ChatComponentText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX;
import static org.lwjgl.opengl.GL11.glGetFloat;

public class BaseChainMode extends AbstractMode {
    public Logger LOG = LogManager.getLogger();
    public final BlockBreaker breaker;


    public BaseChainMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        breaker = new BlockBreaker(modeManager.player, modeManager.world);
        timer = System.currentTimeMillis();
        positionFounder = new ChainFounder(this, center, modeManager.player);
    }

    public int failCounter = 0;
    public long failTimer = 0;
    public long lastTime = System.currentTimeMillis();
    public int tickBreakCount = 0;
    public int allBreakCount = 0;
    @Override
    public void mainLogic() {
        if (allBreakCount >= blockLimit - 1) {
            LOG.info("[渲染] 方块数量达标");
            shutdown();
            return;
        }
        lastTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - lastTime <= taskTimeLimit) {
            if (tickBreakCount >= perTickBlock) break;
            Vector3i pos = positionFounder.cache.poll();
            if (pos == null) {
                if (failCounter == 0) failTimer = System.currentTimeMillis();
                if (System.currentTimeMillis() - failTimer >= heartbeatTimeout) {
                    /*LOG.info("[渲染] 连续错误时间过长");*/
                    shutdown(); // 没有获取到点的时间超过最大等待限制终止任务
                }
                failCounter++;
                return;
            }
            failCounter = 0;
            if (checkCanBreak(pos)) {
                if (!isRenderMode) breaker.tryHarvestBlock(pos);
                else {
                    modeManager.renderCache.add(pos);
                }
                tickBreakCount++;
                allBreakCount++;
            }
        }
        tickBreakCount = 0;
    }

    public long timer;
    @Override
    public void unregister() {
        super.unregister();
        long totalTime = System.currentTimeMillis() - timer;
        // 分割秒和毫秒
        int seconds = (int)(totalTime / 1000);  // 秒数
        long milliseconds = totalTime % 1000;  // 毫秒数
        String message = "本次共挖掘: " + allBreakCount + "个方块"
            + " 共用时: " + seconds + "秒"
            + milliseconds + "毫秒";
        ChatComponentText text = new ChatComponentText(message);
        modeManager.player.addChatMessage(text);
    }
}
