package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 阶段5 执行上下文跨线程注册表（E1-c 独立 Registry 方案）。
 *
 * <p>解决"shadowQueue 局部变量断链"——阶段4 worker 的 {@code shadowQueue} 是
 * {@link club.heiqi.qz_miner.chain.planner.ChainPlanningEventBridge#runShadowSlice} 方法局部变量，
 * 随栈帧销毁，而 {@link club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted} 事件只携带
 * {@code int totalTargets}，不带队列引用。阶段5 执行订阅者在当前代码下根本拿不到新链路算出的
 * 目标集合（决策文档 §"阶段5 入口致命卡点"）。</p>
 *
 * <p>本类用 {@code ConcurrentHashMap<UUID, ChainExecutionContext>} 桥接：worker 完成时
 * {@link #put} 入队列+代际，主线程执行订阅者收到 {@link club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted}
 * 后 {@link #get(UUID, int)} 领取。按 {@code UUID + gen} 双校验天然复用 gen 传递链做陈旧判定，
 * 不破坏 {@link club.heiqi.qz_miner.chain.eventbus.event.ChainEvent} 不可变契约。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本类只承载数据引用，不触碰世界、不切态、不破坏方块。</li>
 *   <li><b>I4</b>：跨线程安全——worker 线程 put，主线程消费订阅者 get，
 *       {@link ConcurrentHashMap} 提供 happens-before 可见性。</li>
 *   <li><b>I10</b>：本类 <b>不</b>写状态机，gen 字段从 context 透传。</li>
 * </ul>
 *
 * <h3>gen 校验策略（决策文档 §"陈旧 gen 领取被拒"）</h3>
 * <ul>
 *   <li>{@link #put}：同 UUID 已有 context 时，仅当新 gen &gt; 旧 gen 才覆盖（防陈旧 gen 覆盖新 gen）。</li>
 *   <li>{@link #get(UUID, int)}：传入 gen 必须等于 context 内 gen 才返回，否则返回 null
 *       （陈旧 gen 领取被拒——例如 worker 已被新一代规划取代，但旧 publish PlanCompleted 迟到）。</li>
 * </ul>
 *
 * <h3>阶段7 回填点</h3>
 * <p>{@link #remove(UUID)} 留阶段7「看门狗 + 生命周期收口」调用（玩家退出/重生/切维度/I7 触发），
 * 防止玩家登出后槽永驻。执行订阅者真实破坏消费完后由其自行清理。</p>
 */
public final class ChainExecutionContextRegistry {

    /**
     * 按玩家 UUID 分槽的执行上下文容器。
     *
     * <p>key=玩家 UUID，value=该玩家当前活跃的执行上下文（最新 gen）。
     * 同玩家跨代际覆盖（gen 单调递增校验）。</p>
     */
    private final ConcurrentMap<UUID, ChainExecutionContext> contexts =
            new ConcurrentHashMap<UUID, ChainExecutionContext>();

    /**
     * 登记执行上下文（bridge worker 完成路径调用）。
     *
     * <p>gen 校验：同 UUID 已有 context 时，仅当新 gen &gt; 旧 gen 才覆盖。
     * 防御性保护陈旧 worker 迟到 put 覆盖新一代 context。</p>
     *
     * @param context 执行上下文（playerUUID + generation + targets）
     */
    public void put(ChainExecutionContext context) {
        UUID playerUUID = context.getPlayerUUID();
        int newGen = context.getGeneration();
        // 原子化 compare-and-set：仅在 newGen > oldGen 时覆盖
        contexts.compute(playerUUID, (uuid, existing) -> {
            if (existing == null) {
                return context;
            }
            if (newGen > existing.getGeneration()) {
                return context;
            }
            // 陈旧 worker 迟到 put，保留现有 context（理论上不应出现，状态机 genCheck 兜底；
            // 但本注册表是独立数据通道，防御性保护不依赖状态机）
            return existing;
        });
    }

    /**
     * 按玩家 + 代际领取执行上下文（主线程执行订阅者调用）。
     *
     * <p>gen 校验：传入 gen 必须等于 context 内 gen 才返回，否则返回 null。
     * 陈旧 gen 领取被拒——执行订阅者收到 null 应 debug 日志并 return，
     * 不消费、不 publish ExecutionFinished。</p>
     *
     * @param playerUUID 玩家 UUID
     * @param gen        期望代际（通常取自 PlanCompleted.getGeneration()）
     * @return 匹配的执行上下文，陈旧/不存在返回 null
     */
    public ChainExecutionContext get(UUID playerUUID, int gen) {
        ChainExecutionContext context = contexts.get(playerUUID);
        if (context == null) {
            return null;
        }
        if (context.getGeneration() != gen) {
            return null;
        }
        return context;
    }

    /**
     * 移除玩家执行上下文（阶段7 生命周期收口 / 阶段5 执行订阅者消费完成后调用）。
     *
     * @param playerUUID 玩家 UUID
     */
    public void remove(UUID playerUUID) {
        contexts.remove(playerUUID);
    }

    /**
     * 快照当前所有活跃执行上下文（供 ServerTickEvent 遍历消费）。
     *
     * <p>返回 {@link ConcurrentMap#values()} 的弱一致快照，遍历期间 worker 的 put 不影响遍历语义。
     * 阶段8 块2 起真实破坏消费场景下，每 tick 主线程遍历此快照 poll 目标队列真实破坏。</p>
     *
     * @return 当前所有活跃执行上下文的只读视图
     */
    public java.util.Collection<ChainExecutionContext> snapshot() {
        return contexts.values();
    }
}
