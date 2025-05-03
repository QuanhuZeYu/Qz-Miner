package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.RectangularFounder;
import club.heiqi.qz_miner.minerModes.rightClicker.RightClicker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.joml.Vector3i;

/**
 * 挖掘一切可以挖掘的
 */
public class RectangularMode extends AbstractMode {
    public final BlockBreaker breaker;
    public final RightClicker rightClicker;

    public RectangularMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        breaker = new BlockBreaker(player, world);
        rightClicker = new RightClicker(player, world);
        timer = System.currentTimeMillis();
        positionFounder = new RectangularFounder(this, center, player);
    }

    public int failCounter = 0;
    public long failTimer = 0;
    public long lastTime = System.currentTimeMillis();
    public int tickBreakCount = 0;
    public int allBreakCount = 0;
    @Override
    public void mainLogic() {
        lastTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - lastTime <= taskTimeLimit) {
            Vector3i pos = positionFounder.cache.poll();
            if (pos == null) {
                if (failCounter == 0) failTimer = System.currentTimeMillis();
                if (System.currentTimeMillis() - failTimer >= heartbeatTimeout) {
                    LOG.info("心跳超时结束");
                    shutdown(); // 没有获取到点的时间超过最大等待限制终止任务
                }
                failCounter++;
                return;
            }
            failCounter = 0;
            if (checkCanBreak(pos)) {
                if (isRenderMode.get()) modeManager.renderCache.add(pos);
                else if (isInteractMode.get()) {
                    rightClicker.rightClick(pos);
                    tickBreakCount++;
                    allBreakCount++;
                } else {
                    breaker.tryHarvestBlock(pos);
                    tickBreakCount++;
                    allBreakCount++;
                }
                // 判断挖掘数量是否终止
                if (allBreakCount >= Config.blockLimit) {
                    LOG.info("数量达到终止");
                    shutdown();
                    return;
                }
                if (tickBreakCount >= perTickBlock) break;
            }
        }
        tickBreakCount = 0;
    }

    public long timer;
    @Override
    public void unregister() {
        sendMessage();
        rightClicker.dropCapture();
        super.unregister();
    }

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

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
