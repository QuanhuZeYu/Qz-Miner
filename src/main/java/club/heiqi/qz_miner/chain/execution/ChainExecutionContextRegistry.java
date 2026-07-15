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
 * 后 {@link #get(UUID, int, long)} 领取。按 {@code UUID + gen + serverRoundId} 三元校验做陈旧判定，
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
 * <h3>三元身份校验策略（决策文档 §"陈旧 gen 领取被拒"）</h3>
 * <ul>
 *   <li>{@link #put}：新 gen 覆盖旧 gen；同 gen 的不同 round 由后到达的新 round 接管。</li>
 *   <li>{@link #get(UUID, int, long)}：传入 gen 与 round 必须同时匹配 context，否则返回 null，
 *       防止旧轮 publish PlanCompleted 领取或清理新轮 context。</li>
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
     * <p>三元身份校验：新 gen 覆盖旧 gen；同 gen 的不同 round 由新 round 接管。
     * 后续 get/remove 均要求 round 匹配，防止旧轮事件影响新 context。</p>
     *
     * @param context 执行上下文（playerUUID + generation + targets）
     */
    public void put(ChainExecutionContext context) {
        UUID playerUUID = context.getPlayerUUID();
        int newGen = context.getGeneration();
        // 原子化 compare-and-set：新 generation 或同 generation 的新 round 才接管。
        contexts.compute(playerUUID, (uuid, existing) -> {
            if (existing == null) {
                return context;
            }
            if (newGen > existing.getGeneration()
                    || (newGen == existing.getGeneration()
                    && context.getServerRoundId() != existing.getServerRoundId())) {
                return context;
            }
            // 陈旧 worker 迟到 put，保留现有 context（理论上不应出现，状态机 genCheck 兜底；
            // 但本注册表是独立数据通道，防御性保护不依赖状态机）
            return existing;
        });
    }

    /**
     * 按玩家 + 代际领取兼容旧轮次的执行上下文。
     *
     * <p>兼容入口固定匹配 {@code NO_SERVER_ROUND_ID}；新链路应调用三参重载。</p>
     *
     * @param playerUUID 玩家 UUID
     * @param gen        期望代际（通常取自 PlanCompleted.getGeneration()）
     * @return 匹配的执行上下文，陈旧/不存在返回 null
     */
    public ChainExecutionContext get(UUID playerUUID, int gen) {
        return get(playerUUID, gen, club.heiqi.qz_miner.chain.eventbus.ChainEvent.NO_SERVER_ROUND_ID);
    }

    /**
     * 按玩家、代际与服务端轮次领取执行上下文。
     *
     * @param playerUUID 玩家 UUID
     * @param gen        期望代际
     * @param serverRoundId 期望的不可变服务端轮次 ID
     * @return 三元身份匹配的上下文，否则返回 null
     */
    public ChainExecutionContext get(UUID playerUUID, int gen, long serverRoundId) {
        ChainExecutionContext context = contexts.get(playerUUID);
        if (context == null) {
            return null;
        }
        if (context.getGeneration() != gen || context.getServerRoundId() != serverRoundId) {
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
     * 仅在三元身份匹配时移除上下文，避免旧轮事件清理新轮资源。
     *
     * @param playerUUID 玩家 UUID
     * @param gen 代际
     * @param serverRoundId 不可变服务端轮次 ID
     * @return true 表示移除了匹配上下文
     */
    public boolean remove(UUID playerUUID, int gen, long serverRoundId) {
        ChainExecutionContext context = get(playerUUID, gen, serverRoundId);
        return context != null && contexts.remove(playerUUID, context);
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
