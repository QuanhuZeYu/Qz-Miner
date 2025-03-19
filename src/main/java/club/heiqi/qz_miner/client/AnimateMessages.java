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
import org.joml.Vector2i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@SideOnly(Side.CLIENT)
public class AnimateMessages {
    public static Logger LOG = LogManager.getLogger();
    /**消息需要经过的屏幕坐标百分比，包括起点终点，只包含关键帧*/
    public List<Vector2d> path = new ArrayList<>();
    /**设定的路径时间，少于path.size()-2时自动使用最后一个，为null时向前寻找直到为非null，全为null为非法用法，时间单位为毫秒*/
    public List<Long> duration = Arrays.asList(1_000L);
    /** 预处理后的有效持续时间 */
    public List<Long> effectiveDurations = new ArrayList<>();
    /** 各路径段的缓动强度（0.0~0.5） */
    public List<Double> easingFactors = Arrays.asList(0.2);
    /** 预处理后的有效缓动参数 */
    public List<Double> effectiveEasing = new ArrayList<>();
    /**总持续时间*/
    public long totalDuration = -1;
    /** 消息文本 */
    public String messageText;
    /** 消息颜色 */
    public int messageColor;

    public long startTime;
    public boolean inAnimating = false;
    public boolean useFuncDraw = false;

    public AnimateMessages useFunc(Consumer<Vector2i> consumer) {
        drawFunc = consumer;
        useFuncDraw = true;
        return this;
    }

    public AnimateMessages register(List<Vector2d> paths, List<Long> durations) {
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
        effectiveEasing = new ArrayList<>();
        // 预处理缓动参数
        for (int i = 0; i < segments; i++) {
            Double factor = getValidEasing(i);
            effectiveEasing.add(Math.min(0.5, Math.max(0.0, factor))); // 钳制在0~0.5范围
        }

        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        this.startTime = System.currentTimeMillis();
        this.inAnimating = true;
        return this;
    }

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
        effectiveEasing = new ArrayList<>();
        // 预处理缓动参数
        for (int i = 0; i < segments; i++) {
            Double factor = getValidEasing(i);
            effectiveEasing.add(Math.min(0.5, Math.max(0.0, factor))); // 钳制在0~0.5范围
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
        if (!inAnimating || event.type != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - startTime;
        // 初始化总时长（延迟计算）
        if (totalDuration == -1) {
            totalDuration = effectiveDurations.stream()
                .mapToLong(Long::longValue)
                .sum();
        }
        // 处理动画结束（包含等于的情况）
        if (elapsedTime >= totalDuration) {
            Vector2d end = path.get(path.size()-1);
            if (useFuncDraw) drawInnerFunc(end.x, end.y);
            else drawString(end.x, end.y);
            unRegister();
            return;
        }
        // 安全查找当前动画段
        int currentSegment = 0;
        long accumulatedTime = 0;
        while (currentSegment < effectiveDurations.size()
            && (accumulatedTime + effectiveDurations.get(currentSegment)) <= elapsedTime) {
            accumulatedTime += effectiveDurations.get(currentSegment);
            currentSegment++;
        }
        // 双重保险检查（防止计算误差导致的越界）
        if (currentSegment >= effectiveDurations.size()) {
            unRegister();
            return;
        }
        // 安全获取时间段参数
        Long segmentDuration = effectiveDurations.get(currentSegment);
        float segmentProgress = (float)(elapsedTime - accumulatedTime) / segmentDuration;

        // 边界情况处理（防止除零错误）
        if (segmentDuration <= 0) {
            segmentProgress = 1.0f;
        }
        // 安全获取路径点
        Vector2d start = path.get(currentSegment);
        Vector2d end = path.get(Math.min(currentSegment + 1, path.size() - 1));
        // 应用缓动效果（如果启用）
        if (!effectiveEasing.isEmpty()) {
            double easing = effectiveEasing.get(currentSegment);
            segmentProgress = (float) applyEasing(segmentProgress, easing);
        }
        // 计算插值坐标
        double x = start.x + (end.x - start.x) * segmentProgress;
        double y = start.y + (end.y - start.y) * segmentProgress;
        if (useFuncDraw) drawInnerFunc(x, y);
        else drawString(x, y);
    }

    /**
     * @param xPercent 从左往右的屏幕百分比 0-1
     * @param yPercent 从上到下的百分比 0-1
     */
    public void drawString(double xPercent, double yPercent) {
        FontRenderer fontRender = Minecraft.getMinecraft().fontRenderer;
        int w = getScreenWidth(); int h = getScreenHeight();
        // xy为左上角坐标
        int x = (int) Math.floor(w*xPercent);
        int y = (int) Math.floor(h*yPercent);
        fontRender.drawString(messageText, x, y, messageColor);
    }

    public Consumer<Vector2i> drawFunc;
    public void drawInnerFunc(double xPercent, double yPercent) {
        FontRenderer fontRender = Minecraft.getMinecraft().fontRenderer;
        int w = getScreenWidth(); int h = getScreenHeight();
        // xy为左上角坐标
        int x = (int) Math.floor(w*xPercent);
        int y = (int) Math.floor(h*yPercent);
        Vector2i vec = new Vector2i(x, y);
        drawFunc.accept(vec);
    }

    /**
     * 应用缓动函数（三次贝塞尔曲线实现）
     * @param progress 原始进度（0.0~1.0）
     * @param factor 缓动强度（0.0~0.5）
     * @return 缓动后的进度
     */
    private double applyEasing(double progress, double factor) {
        // 使用自定义三次贝塞尔曲线
        // P0(0,0), P1(factor,0), P2(1-factor,1), P3(1,1)
        double t = progress;
        double t2 = t * t;
        double t3 = t2 * t;
        double mt = 1 - t;
        double mt2 = mt * mt;
        double mt3 = mt2 * mt;
        return 3 * factor * mt2 * t + 3 * (1 - factor) * mt * t2 + t3;
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

    /**
     * 获取有效的缓动参数
     */
    public Double getValidEasing(int segmentIndex) {
        // 逻辑与duration预处理类似
        if (segmentIndex < easingFactors.size()) {
            Double f = easingFactors.get(segmentIndex);
            if (f != null) return f;
        }

        for (int i = segmentIndex-1; i >= 0; i--) {
            if (i < easingFactors.size() && easingFactors.get(i) != null) {
                return easingFactors.get(i);
            }
        }

        if (!easingFactors.isEmpty()) {
            for (int i = easingFactors.size()-1; i >=0 ; i--) {
                if (easingFactors.get(i) != null) {
                    return easingFactors.get(i);
                }
            }
        }
        return 0.0; // 默认无缓动
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
