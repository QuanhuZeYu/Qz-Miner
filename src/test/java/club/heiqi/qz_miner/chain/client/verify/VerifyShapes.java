package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T7 独立验证用形状工厂（preview-verifier 自持，不复用 owner 测试夹具）。
 *
 * <p>所有序列均确定化：同参数重复调用产生逐元素相同的列表，便于做逐字节回归比较。</p>
 */
public final class VerifyShapes {

    private VerifyShapes() {
    }

    /** 单方块。 */
    public static List<ChainTarget> single(int x, int y, int z) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(1);
        targets.add(new ChainTarget(x, y, z));
        return targets;
    }

    /** 沿 X 轴的连续线段。 */
    public static List<ChainTarget> line(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index, 0, 0));
        }
        return targets;
    }

    /** 沿 X 轴等距散点（stride >= 3 时互不相邻）。 */
    public static List<ChainTarget> scatteredX(int count, int stride) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(index * stride, 0, 0));
        }
        return targets;
    }

    /** n×n×n 实心立方。 */
    public static List<ChainTarget> solidCube(int n) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(n * n * n);
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                for (int z = 0; z < n; z++) {
                    targets.add(new ChainTarget(x, y, z));
                }
            }
        }
        return targets;
    }

    /** n×n 单层平面。 */
    public static List<ChainTarget> plane(int n) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(n * n);
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                targets.add(new ChainTarget(x, 0, z));
            }
        }
        return targets;
    }

    /** L 形折线（含一个拐角接头）。 */
    public static List<ChainTarget> lShape(int arm) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(arm * 2 - 1);
        for (int x = 0; x < arm; x++) {
            targets.add(new ChainTarget(x, 0, 0));
        }
        for (int z = 1; z < arm; z++) {
            targets.add(new ChainTarget(0, 0, z));
        }
        return targets;
    }

    /** 同一坐标重复 count 次。 */
    public static List<ChainTarget> duplicated(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(7, 64, -3));
        }
        return targets;
    }

    /** 棱接触（对角面相邻）。 */
    public static List<ChainTarget> edgeContact() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(2);
        targets.add(new ChainTarget(0, 0, 0));
        targets.add(new ChainTarget(1, 1, 0));
        return targets;
    }

    /** 角接触。 */
    public static List<ChainTarget> cornerContact() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(2);
        targets.add(new ChainTarget(0, 0, 0));
        targets.add(new ChainTarget(1, 1, 1));
        return targets;
    }

    /**
     * 稀疏三维晶格散点：格距 4，保证目标之间互不相邻（每目标 12 条可见边）。
     *
     * <p>用位段展开而非随机数，保证 count <= 262144 时无碰撞且可复现。</p>
     */
    public static List<ChainTarget> deterministicScatter(int count) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            int x = (index & 0x3FF) * 4;
            int y = ((index >>> 10) & 0x3F) * 4;
            int z = ((index >>> 16) & 0x3F) * 4;
            targets.add(new ChainTarget(x, y, z));
        }
        return targets;
    }
}
