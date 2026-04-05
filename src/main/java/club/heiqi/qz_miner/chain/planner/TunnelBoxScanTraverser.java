package club.heiqi.qz_miner.chain.planner;

/**
 * 3x3x半径的指向性隧道遍历器。
 */
public class TunnelBoxScanTraverser implements ChainTraverser {

    private final int face;
    private final ChainTarget forward;
    private final ChainTarget lateralA;
    private final ChainTarget lateralB;

    public TunnelBoxScanTraverser(int face) {
        this.face = normalizeFace(face);
        this.forward = resolveForward(this.face);
        this.lateralA = resolveLateralA(this.face);
        this.lateralB = resolveLateralB(this.face);
    }

    @Override
    public void seed(ChainSearchContext context) {
        context.getVisited().add(context.getOrigin());
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
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
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
}
