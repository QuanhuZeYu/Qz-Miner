package club.heiqi.qz_miner.chain.planner;

/**
 * 区块清除遍历器：16×16截面，沿玩家朝向方向挖掘指定深度。
 * 截面严格对齐区块边界（16的倍数）。
 */
public class ChunkClearTraverser implements ChainTraverser {

    private final int face;
    private final ChainTarget forward;
    private final ChainTarget lateralA;
    private final ChainTarget lateralB;
    private int aMin;
    private int aMax;
    private int bMin;
    private int bMax;

    public ChunkClearTraverser(int face) {
        this.face = normalizeFace(face);
        this.forward = resolveForward(this.face);
        this.lateralA = resolveLateralA(this.face);
        this.lateralB = resolveLateralB(this.face);
    }

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();

        // 确定 lateralA 和 lateralB 对应的世界坐标轴
        int axisA = resolveAxis(lateralA); // 0:X, 1:Y, 2:Z
        int axisB = resolveAxis(lateralB);

        // 获取原点在对应轴上的坐标
        int coordA = getCoordByAxis(origin, axisA);
        int coordB = getCoordByAxis(origin, axisB);

        // 计算区块起点（16的倍数）
        int chunkStartA = (coordA >> 4) << 4; // Math.floorDiv(coordA, 16) * 16
        int chunkStartB = (coordB >> 4) << 4;

        // 计算原点相对于区块起点的偏移
        int offsetA = coordA - chunkStartA;
        int offsetB = coordB - chunkStartB;

        // 截面范围：从 -offsetA 到 15-offsetA，确保总共16格
        // 但为了简化，我们固定使用 -8 到 7，以原点为中心
        // 根据用户需求，需要对齐区块边界，所以应该使用区块起点
        // 我们调整偏移量，使截面以区块起点为基准

        // 计算 lateralA 方向的偏移范围：从 -offsetA 到 15-offsetA
        aMin = -offsetA;
        aMax = 15 - offsetA;
        bMin = -offsetB;
        bMax = 15 - offsetB;

        context.getVisited().add(origin);
        enqueueSlice(context, 0);
        context.setScanDepth(1);
    }

    @Override
    public boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;

        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return false;
        }

        while (processed < maxNodes) {
            if (context.getCurrentFrontier().isEmpty()) {
                if (!enqueueNextSlice(context)) {
                    return false;
                }
            }

            ChainTarget current = context.getCurrentFrontier().poll();
            if (current == null) {
                continue;
            }

            if (!matcher.matches(current)) {
                processed++;
                continue;
            }

            consumer.accept(current);
            context.incrementConfirmedCount();
            processed++;

            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                break;
            }
        }

        return context.getConfirmedCount() < context.getMaxTargets()
            && (!context.getCurrentFrontier().isEmpty() || context.getScanDepth() < context.getMaxRadius());
    }

    private boolean enqueueNextSlice(ChainSearchContext context) {
        int nextDepth = context.getScanDepth();
        if (nextDepth >= context.getMaxRadius()) {
            return false;
        }

        enqueueSlice(context, nextDepth);
        context.setScanDepth(nextDepth + 1);
        return true;
    }

    private void enqueueSlice(ChainSearchContext context, int depth) {
        ChainTarget origin = context.getOrigin();

        for (int a = aMin; a <= aMax; a++) {
            for (int b = bMin; b <= bMax; b++) {
                ChainTarget candidate = new ChainTarget(
                    origin.getX() + forward.getX() * depth + lateralA.getX() * a + lateralB.getX() * b,
                    origin.getY() + forward.getY() * depth + lateralA.getY() * a + lateralB.getY() * b,
                    origin.getZ() + forward.getZ() * depth + lateralA.getZ() * a + lateralB.getZ() * b);

                if (!context.getVisited().add(candidate)) {
                    continue;
                }

                if (!context.canTraverse(candidate)) {
                    continue;
                }

                context.getCurrentFrontier().add(candidate);
            }
        }
    }

    private static int normalizeFace(int face) {
        return face < 0 || face > 5 ? 1 : face;
    }

    private static ChainTarget resolveForward(int face) {
        switch (face) {
            case 0:
                return new ChainTarget(0, -1, 0);
            case 1:
                return new ChainTarget(0, 1, 0);
            case 2:
                return new ChainTarget(0, 0, -1);
            case 3:
                return new ChainTarget(0, 0, 1);
            case 4:
                return new ChainTarget(-1, 0, 0);
            case 5:
            default:
                return new ChainTarget(1, 0, 0);
        }
    }

    private static ChainTarget resolveLateralA(int face) {
        switch (face) {
            case 0:
            case 1:
                return new ChainTarget(1, 0, 0);
            case 2:
            case 3:
                return new ChainTarget(1, 0, 0);
            case 4:
            case 5:
            default:
                return new ChainTarget(0, 1, 0);
        }
    }

    private static ChainTarget resolveLateralB(int face) {
        switch (face) {
            case 0:
            case 1:
                return new ChainTarget(0, 0, 1);
            case 2:
            case 3:
                return new ChainTarget(0, 1, 0);
            case 4:
            case 5:
            default:
                return new ChainTarget(0, 0, 1);
        }
    }

    private static int resolveAxis(ChainTarget vec) {
        if (vec.getX() != 0) return 0;
        if (vec.getY() != 0) return 1;
        return 2;
    }

    private static int getCoordByAxis(ChainTarget target, int axis) {
        switch (axis) {
            case 0: return target.getX();
            case 1: return target.getY();
            case 2: return target.getZ();
            default: return 0;
        }
    }
}
