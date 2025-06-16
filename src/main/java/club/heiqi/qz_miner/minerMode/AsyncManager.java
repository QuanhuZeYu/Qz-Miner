package club.heiqi.qz_miner.minerMode;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RunnableFuture;

/**
 * 异步执行器-将连锁的多线程执行转换为单线程<br/>
 * 每个游戏只会有一个异步执行器（单线程的泪）
 */
public class AsyncManager {
    /**每个生命周期最大可执行时间*/
    public static int MAX_WORK_TIME = 25; // 0.025s 25ms 一半tick

    /**存放待处理的坐标 用于将不安全的多线程世界操作转换为单线程检查 无返回值的用法*/
    public static List<Runnable> functions = new ArrayList<>();

    /**有返回值的用法*/
    public static List<RunnableFuture<Vector3i>> futures = new ArrayList<>();

    public static void pollTask(Runnable task) {
        functions.add(task);
    }

    @SubscribeEvent
    public static void preTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.ServerTickEvent.Phase.START || (functions.isEmpty() && futures.isEmpty())) return;
        // tick 开始时执行逻辑
        long startTime = System.currentTimeMillis();
        Iterator<Runnable> runnableIterator = functions.iterator();
        Iterator<RunnableFuture<Vector3i>> futureIterator = futures.iterator();
        while(System.currentTimeMillis() - startTime <= MAX_WORK_TIME) {
            boolean stop = false;
            if (!runnableIterator.hasNext()) stop = true;
            else {
                Runnable next = runnableIterator.next();
                next.run();
            }
            if (!futureIterator.hasNext()) stop = true;
            else {
                Runnable future = futureIterator.next();
                future.run();
            }
            if (stop) break;
        }
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(AsyncManager.class);
        FMLCommonHandler.instance().bus().register(AsyncManager.class);
    }
}
