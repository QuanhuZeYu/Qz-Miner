package club.heiqi.qz_miner.chain.state;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * 玩家级掉落缓冲。
 *
 * 与单次连锁会话解耦，避免会话提前清理时丢失尚未释放的掉落。
 */
public final class ChainPlayerDropBuffer {

    private final List<ItemStack> pendingDrops = new ArrayList<ItemStack>();
    private Integer fallbackDimension;
    private double fallbackX;
    private double fallbackY;
    private double fallbackZ;

    /**
     * 合并一组掉落到缓冲区。
     *
     * @param drops 掉落列表
     */
    public synchronized void addAll(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return;
        }

        for (ItemStack drop : drops) {
            add(drop);
        }
    }

    /**
     * 合并单个掉落到缓冲区。
     *
     * @param drop 掉落物
     */
    public synchronized void add(ItemStack drop) {
        if (drop == null || drop.stackSize <= 0) {
            return;
        }

        for (ItemStack existing : pendingDrops) {
            if (existing.isItemEqual(drop)
                && ItemStack.areItemStackTagsEqual(existing, drop)) {
                existing.stackSize += drop.stackSize;
                return;
            }
        }

        pendingDrops.add(drop.copy());
    }

    /**
     * 取出当前所有待释放掉落并清空缓冲区。
     *
     * @return 掉落快照
     */
    public synchronized List<ItemStack> drain() {
        List<ItemStack> snapshot = new ArrayList<ItemStack>(pendingDrops);
        pendingDrops.clear();
        return snapshot;
    }

    /**
     * 返回当前缓冲区中的掉落数量。
     *
     * @return 掉落栈数量
     */
    public synchronized int size() {
        return pendingDrops.size();
    }

    /**
     * 记录玩家不可用时的兜底释放坐标。
     *
     * @param dimension 维度 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     */
    public synchronized void rememberFallbackTarget(int dimension, double x, double y, double z) {
        this.fallbackDimension = Integer.valueOf(dimension);
        this.fallbackX = x;
        this.fallbackY = y;
        this.fallbackZ = z;
    }

    /**
     * 获取当前记录的兜底释放坐标。
     *
     * @return 兜底释放坐标，如果不存在则返回 null
     */
    public synchronized FallbackTarget getFallbackTarget() {
        if (fallbackDimension == null) {
            return null;
        }
        return new FallbackTarget(fallbackDimension.intValue(), fallbackX, fallbackY, fallbackZ);
    }

    /**
     * 判断缓冲区是否为空。
     *
     * @return 是否为空
     */
    public synchronized boolean isEmpty() {
        return pendingDrops.isEmpty();
    }

    /**
     * 清空缓冲区。
     */
    public synchronized void clear() {
        pendingDrops.clear();
    }

    /**
     * 玩家掉落兜底释放坐标快照。
     */
    public static final class FallbackTarget {
        private final int dimension;
        private final double x;
        private final double y;
        private final double z;

        private FallbackTarget(int dimension, double x, double y, double z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getDimension() {
            return dimension;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }
    }
}
