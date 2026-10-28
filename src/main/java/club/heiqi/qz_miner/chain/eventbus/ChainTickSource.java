package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

/** 为连锁事件提供诊断 tick 与单调纳秒戳，服务端和客户端共用。 */
public final class ChainTickSource {

    private ChainTickSource() {
        // 工具类。
    }

    /**
     * 获取当前服务端 tick 计数。
     *
     * <p>供 {@code publish} 路径填 {@code ChainEvent.serverTick}。
     * 若当前未运行服务端实例（如客户端早期 init 阶段），或 Forge 运行时未启动
     * （如纯 JVM 单测环境 {@code FMLCommonHandler.instance()} 触发 {@code ExceptionInInitializerError}），
     * 均返回 {@code -1L} 诊断占位；线程身份由调用方所属执行入口确定。</p>
     *
     * @return 当前服务端 {@code getTickCounter()}，无服务端实例或无 Forge 运行时时返回 {@code -1L}
     */
    public static long currentServerTick() {
        try {
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            return server == null ? -1L : server.getTickCounter();
        } catch (LinkageError | RuntimeException e) {
            // Forge 尚未就绪时使用诊断占位，继续原事件流程。
            return -1L;
        }
    }

    /**
     * 获取当前纳秒戳，仅供诊断/时序分析。
     *
     * @return {@link System#nanoTime()}
     */
    public static long nowNanos() {
        return System.nanoTime();
    }
}