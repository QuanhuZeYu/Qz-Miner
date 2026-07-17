package club.heiqi.qz_miner.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

/**
 * 客户端主线程调度器。
 */
@SideOnly(Side.CLIENT)
public final class ClientMainThreadDispatcher {

    private ClientMainThreadDispatcher() {}

    /**
     * 在客户端主线程执行任务。
     *
     * @param task 待执行任务
     */
    public static void run(Runnable task) {
        tryRun(task);
    }

    /**
     * 尝试在客户端主线程执行任务。
     *
     * @param task 待执行任务
     * @return 客户端可用且任务已执行或入队时为 true
     */
    public static boolean tryRun(Runnable task) {
        if (task == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return false;
        }

        if (minecraft.func_152345_ab()) {
            task.run();
            return true;
        }

        minecraft.func_152344_a(task);
        return true;
    }
}
