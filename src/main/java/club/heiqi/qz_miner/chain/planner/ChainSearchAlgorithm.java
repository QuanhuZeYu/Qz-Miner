package club.heiqi.qz_miner.chain.planner;

import java.util.Arrays;
import java.util.List;

/**
 * 连锁搜索算法。
 *
 * BFS 从中心向外扩散，状态分为三层：
 * - currentFrontier：当前轮需要处理的节点（从上一轮的邻居中选出的候选点）
 * - nextFrontier：下一轮需要处理的节点（从当前轮扩展出来的邻居）
 * - consumer 接收到的节点：已确认可挖掘的点
 *
 * 处理逻辑：
 * 1. 从 currentFrontier 取出一个节点
 * 2. 检查该节点是否仍然是同种方块（可能已被消费端挖掉）
 * 3. 如果是且可挖掘，通知 consumer 接收，然后扩展邻居到 nextFrontier
 * 4. 如果不是（已被挖掉），跳过该节点但不扩展邻居
 * 5. currentFrontier 处理完后，将 nextFrontier 旋转为 currentFrontier
 *
 * 重入保证：无论何时暂停和恢复，都可以从 currentFrontier 继续处理。
 */
public final class ChainSearchAlgorithm {

    private static final List<ChainTarget> NEIGHBOR_OFFSETS = Arrays.asList(
        new ChainTarget(1, 0, 0),
        new ChainTarget(-1, 0, 0),
        new ChainTarget(0, 1, 0),
        new ChainTarget(0, -1, 0),
        new ChainTarget(0, 0, 1),
        new ChainTarget(0, 0, -1));

    private ChainSearchAlgorithm() {}

    /**
     * 执行一轮搜索分片。
     *
     * @param context     搜索上下文
     * @param maxNodes    本轮最多处理的节点数
     * @param matcher     节点匹配器（检查是否可挖掘）
     * @param consumer    消费者（接收已确认可挖掘的节点）
     * @return 是否还有更多节点可继续处理
     */
    public static boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;

        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return false;
        }

        while (processed < maxNodes && !context.getCurrentFrontier().isEmpty()) {
            ChainTarget current = context.getCurrentFrontier().poll();
            if (current == null) {
                break;
            }

            if (!context.canTraverse(current)) {
                processed++;
                continue;
            }

            if (!matcher.matches(current)) {
                processed++;
                continue;
            }

            consumer.accept(current);
            context.incrementConfirmedCount();

            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                processed++;
                break;
            }

            for (ChainTarget offset : NEIGHBOR_OFFSETS) {
                ChainTarget next = new ChainTarget(
                    current.getX() + offset.getX(),
                    current.getY() + offset.getY(),
                    current.getZ() + offset.getZ());

                if (!context.getVisited().add(next)) {
                    continue;
                }

                if (getDistance(next, context.getOrigin()) > context.getMaxRadius()) {
                    continue;
                }

                if (context.getConfirmedCount() >= context.getMaxTargets()) {
                    break;
                }

                if (!context.canTraverse(next)) {
                    continue;
                }

                context.getNextFrontier().add(next);
            }

            processed++;
        }

        if (context.getCurrentFrontier().isEmpty() && !context.getNextFrontier().isEmpty()) {
            while (!context.getNextFrontier().isEmpty()) {
                context.getCurrentFrontier().add(context.getNextFrontier().poll());
            }
        }

        return !context.getCurrentFrontier().isEmpty();
    }

    private static int getDistance(ChainTarget a, ChainTarget b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }
}
