package club.heiqi.qz_miner.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2d;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class AnimateMessages {
    public static Logger LOG = LogManager.getLogger();
    /**消息需要经过的屏幕坐标百分比，包括起点终点，只包含关键帧*/
    public List<Vector2d> path = new ArrayList<>();
    /**设定的路径时间，少于path.size()-2时自动使用最后一个，为null时向前寻找直到为非null，全为null为非法用法，时间单位为毫秒*/
    public List<Long> duration = new ArrayList<>();
    /** 预处理后的有效持续时间 */
    public List<Long> effectiveDurations = new ArrayList<>();
    /**总持续时间*/
    public long totalDuration = -1;
    /** 消息文本 */
    public String messageText;
    /** 消息颜色 */
    public int messageColor;

    public long startTime;
    public boolean inAnimating;

    public AnimateMessages register(String text, int color, List<Vector2d> paths, List<Long> durations) {
        if (paths.size() < 2) {
            LOG.warn("注册参数 path 不合法, 参数最少需要包含起点终点");
            return null;
        }
        if (durations.isEmpty()) {
            LOG.warn("注册参数 duration 不合法，最少需要一个");
            return null;
        }
        this.path = paths; this.duration = durations;
        // 预处理持续时间
        effectiveDurations = new ArrayList<>();
        int segments = path.size()-1;
        for (int i = 0; i < segments; i++) {
            Long duration = getValidDuration(i);
            effectiveDurations.add(duration);
        }
        this.messageText = text;
        this.messageColor = color;

        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        this.startTime = System.currentTimeMillis();
        this.inAnimating = true;
        return this;
    }

    public void unRegister() {
        MinecraftForge.EVENT_BUS.unregister(this);
        FMLCommonHandler.instance().bus().unregister(this);
        inAnimating = false;
    }

    @SubscribeEvent
    public void update(RenderGameOverlayEvent.Post event) {
        if (!inAnimating) {
            return;
        }
        if (!(event.type == RenderGameOverlayEvent.ElementType.TEXT)) {
            return; // 如果不是字体渲染阶段则跳过
        }
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - startTime;
        if (totalDuration == -1) totalDuration = effectiveDurations.stream().mapToLong(Long::longValue).sum();
        // 时间结束终止
        if (elapsedTime > totalDuration) {
            Vector2d end = path.get(path.size()-1);
            drawString(messageText, end.x, end.y, messageColor);
            unRegister();
            return;
        }
        // 选取动画时间段
        int currentSegment = 0;
        long accumulatedTime = 0;
        while (currentSegment < effectiveDurations.size() &&
            accumulatedTime + effectiveDurations.get(currentSegment) <= elapsedTime) {
            accumulatedTime += effectiveDurations.get(currentSegment);
            currentSegment++;
        }
        // 计算插值比例
        float segmentProgress = (float)(elapsedTime - accumulatedTime) / effectiveDurations.get/*此处报IndexOutOfBoundsException*/(currentSegment);
        Vector2d start = path.get(currentSegment);
        Vector2d end = path.get(currentSegment + 1);
        double x = start.x + (end.x - start.x) * segmentProgress;
        double y = start.y + (end.y - start.y) * segmentProgress;
        drawString(messageText, x, y, messageColor);
    }

    /**
     * @param text 需要绘制的字符串
     * @param xPercent 从左往右的屏幕百分比 0-1
     * @param yPercent 从上到下的百分比 0-1
     * @param color 十六进制颜色 0xFFFFFF
     */
    public void drawString(String text, double xPercent, double yPercent, int color) {
        FontRenderer fontRender = Minecraft.getMinecraft().fontRenderer;
        int w = getScreenWidth(); int h = getScreenHeight();
        // xy为左上角坐标
        int x = (int) Math.floor(w*xPercent);
        int y = (int) Math.floor(h*yPercent);
        fontRender.drawString(text, x, y, color);
    }

    /**
     * 获取有效的持续时间
     */
    public Long getValidDuration(int segmentIndex) {
        // 1. 优先使用当前索引的duration
        if (segmentIndex < duration.size()) {
            Long d = duration.get(segmentIndex);
            if (d != null) return d;
        }
        // 2. 向前查找非空值
        for (int i = segmentIndex - 1; i >= 0; i--) {
            if (i < duration.size() && duration.get(i) != null) {
                return duration.get(i);
            }
        }
        // 3. 向后查找非空值（处理索引超限）
        if (!duration.isEmpty()) {
            for (int i = duration.size() - 1; i >= 0; i--) {
                if (duration.get(i) != null) {
                    return duration.get(i);
                }
            }
        }
        throw new IllegalStateException("没有找到有效的持续时间参数");
    }

    public static ScaledResolution getScale() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    public static int getScreenWidth() {
        return getScale().getScaledWidth();
    }

    public static int getScreenHeight() {
        return getScale().getScaledHeight();
    }
}
