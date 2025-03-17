package club.heiqi.qz_miner.minerModes.rangeMode;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.RectangularFounder;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.RectangularMineralFounder;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.BlockOresAbstract;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.List;

public class RectangularMineralMode extends AbstractMode {
    public final BlockBreaker breaker;

    public RectangularMineralMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        breaker = new BlockBreaker(player, world);
        timer = System.currentTimeMillis();
        positionFounder = new RectangularMineralFounder(this, center, player);
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
                if (!isRenderMode.get()) breaker.tryHarvestBlock(pos);
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
        if (isRenderMode.get()) return;
        if (!modeManager.getPrintResult()) return;
        long totalTime = System.currentTimeMillis() - timer;
        // 分割秒和毫秒
        int seconds = (int)(totalTime / 1000);  // 秒数
        long milliseconds = totalTime % 1000;  // 毫秒数
        String message = "本次共挖掘: " + allBreakCount + "个方块"
            + " 共用时: " + seconds + "秒"
            + milliseconds + "毫秒";
        ChatComponentText text = new ChatComponentText(message);
        player.addChatMessage(text);
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
