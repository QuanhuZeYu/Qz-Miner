package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.Entity;

/**
 * 连锁目标坐标。
 */
public final class ChainTarget {

    private final int x;
    private final int y;
    private final int z;
    private final List<ChainTarget> blockTargets;
    private final List<Integer> entityIds;
    private final ChainTargetStorageMode storageMode;

    public ChainTarget(int x, int y, int z) {
        this(x, y, z, Collections.<ChainTarget>emptyList(), Collections.<Integer>emptyList(), ChainTargetStorageMode.BLOCK);
    }

    public ChainTarget(List<ChainTarget> blockTargets, List<Integer> entityIds, ChainTargetStorageMode storageMode) {
        this(getFirstCoordinate(blockTargets, 0), getFirstCoordinate(blockTargets, 1), getFirstCoordinate(blockTargets, 2), blockTargets, entityIds, storageMode);
    }

    private ChainTarget(int x, int y, int z, List<ChainTarget> blockTargets, List<Integer> entityIds, ChainTargetStorageMode storageMode) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockTargets = Collections.unmodifiableList(new ArrayList<ChainTarget>(blockTargets));
        this.entityIds = Collections.unmodifiableList(new ArrayList<Integer>(entityIds));
        this.storageMode = storageMode == null ? ChainTargetStorageMode.BLOCK : storageMode;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public List<ChainTarget> getBlockTargets() {
        return blockTargets;
    }

    public List<Integer> getEntityIds() {
        return entityIds;
    }

    public ChainTargetStorageMode getStorageMode() {
        return storageMode;
    }

    public boolean containsBlocks() {
        return storageMode == ChainTargetStorageMode.BLOCK || storageMode == ChainTargetStorageMode.ALL;
    }

    public boolean containsEntities() {
        return storageMode == ChainTargetStorageMode.ENTITY || storageMode == ChainTargetStorageMode.ALL;
    }

    public static ChainTarget forEntities(List<? extends Entity> entities) {
        List<Integer> entityIds = new ArrayList<Integer>();
        for (Entity entity : entities) {
            if (entity != null) {
                entityIds.add(entity.getEntityId());
            }
        }
        return new ChainTarget(Collections.<ChainTarget>emptyList(), entityIds, ChainTargetStorageMode.ENTITY);
    }

    public static ChainTarget forMixed(List<ChainTarget> blockTargets, List<? extends Entity> entities) {
        List<Integer> entityIds = new ArrayList<Integer>();
        for (Entity entity : entities) {
            if (entity != null) {
                entityIds.add(entity.getEntityId());
            }
        }
        return new ChainTarget(blockTargets, entityIds, ChainTargetStorageMode.ALL);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChainTarget)) {
            return false;
        }
        ChainTarget other = (ChainTarget) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

    private static int getFirstCoordinate(List<ChainTarget> blockTargets, int axis) {
        if (blockTargets == null || blockTargets.isEmpty()) {
            return 0;
        }
        ChainTarget first = blockTargets.get(0);
        if (first == null) {
            return 0;
        }
        switch (axis) {
            case 0:
                return first.getX();
            case 1:
                return first.getY();
            case 2:
                return first.getZ();
            default:
                return 0;
        }
    }
}
