package club.heiqi.qz_miner.minerModes.chainMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Strict;
import club.heiqi.qz_miner.minerModes.rightClicker.RightClicker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.joml.Vector3i;

public class StrictChainMode extends AbstractMode {
    public final BlockBreaker breaker;
    public final RightClicker rightClicker;

    public StrictChainMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        timer = System.currentTimeMillis();
        breaker = new BlockBreaker(player, world);
        rightClicker = new RightClicker(player, world);
        positionFounder = new ChainFounder_Strict(this, center, player);
    }

    public int failCounter = 0;
    public long failTimer = 0;
    public long lastTime = System.currentTimeMillis();
    public int tickBreakCount = 0;
    public int allBreakCount = 0;
    @Override
    public void mainLogic() {
        if (allBreakCount >= blockLimit - 1) {
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
        String message = "本次共处理: " + allBreakCount + "个方块"
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
