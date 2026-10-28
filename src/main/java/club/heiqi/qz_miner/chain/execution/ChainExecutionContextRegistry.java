package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按玩家保存执行上下文，桥接规划队列与主线程执行。
 *
 * <p>ConcurrentHashMap 发布上下文引用；领取和定向移除同时匹配 UUID、generation 与 serverRoundId。
 * 新代际或同代际的不同轮次可接管槽位，调用方负责先验证当前规划授权。
 * 执行桥在完成、取消、超时及生命周期清理时移除所属上下文。</p>
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
     * 登记已获授权的执行上下文。
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
            // 较早代际或重复登记沿用现有 context。
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
     * 按玩家移除执行上下文，供强制生命周期清理使用。
     *
     * @param playerUUID 玩家 UUID
     */
    public void remove(UUID playerUUID) {
        contexts.remove(playerUUID);
    }

    /**
     * 仅在玩家、代际与轮次匹配时移除所属上下文。
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
     * <p>返回 {@link ConcurrentMap#values()} 的弱一致视图，供主线程逐 tick 遍历并消费目标。</p>
     *
     * @return 当前所有活跃执行上下文的 values 视图
     */
    public java.util.Collection<ChainExecutionContext> snapshot() {
        return contexts.values();
    }
}
