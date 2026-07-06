package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

/**
 * 连锁事件诊断时间戳来源。
 *
 * <p>守 NORTH_STAR 不变量 I4：仅供 {@code publish} 路径填充 {@code ChainEvent.serverTick}/
 * {@code timestampNanos} 诊断字段。事件是输入事件（ChainKeyPressed/BlockBreakObserved/...）时，
 * 调用方保证在主线程或跨线程入队前取值即可，本类不触碰任何主线程语义状态。</p>
 *
 * <p>严格 side-agnostic：不引用 {@code Minecraft.getMinecraft()} 等客户端专属类，
 * 服务端/客户端均可安全调用（但客户端目前无 publish 点，留阶段6预览接入时再考虑）。</p>
 */
public final class ChainTickSource {

    private ChainTickSource() {
        // 工具类，禁止实例化
    }

    /**
     * 获取当前服务端 tick 计数。
     *
     * <p>供 {@code publish} 路径填 {@code ChainEvent.serverTick}。
     * 若当前未运行服务端实例（如客户端早期 init 阶段），或 Forge 运行时未启动
     * （如纯 JVM 单测环境 {@code FMLCommonHandler.instance()} 触发 {@code ExceptionInInitializerError}），
     * 均返回 {@code -1L} 占位，调用方不应基于此做主线程契约推断。</p>
     *
     * @return 当前服务端 {@code getTickCounter()}，无服务端实例或无 Forge 运行时时返回 {@code -1L}
     */
    public static long currentServerTick() {
        try {
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            return server == null ? -1L : server.getTickCounter();
        } catch (LinkageError | RuntimeException e) {
            // 无 Forge 运行时（如纯 JVM 单测）FMLCommonHandler 触发 ExceptionInInitializerError（LinkageError 子类）；
            // 诊断字段回退占位值，不阻断事件流（守 I4 ChainTickSource 仅诊断字段语义）
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