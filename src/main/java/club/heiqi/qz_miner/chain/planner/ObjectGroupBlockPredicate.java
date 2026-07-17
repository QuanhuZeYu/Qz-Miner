package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * 对象组共享谓词：只按显式 registry + metadata 判断，不读取 NBT、TileEntity 或矿辞。
 */
public final class ObjectGroupBlockPredicate {

    private final ObjectGroup group;

    public ObjectGroupBlockPredicate(ObjectGroup group) {
        if (group == null) {
            throw new IllegalArgumentException("group must not be null");
        }
        this.group = group;
    }

    /** 判断目标方块是否属于冻结的对象组。 */
    public boolean matches(World world, ChainTarget target) {
        if (world == null || target == null) {
            return false;
        }
        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air) {
            return false;
        }
        String registry = registryName(block);
        int meta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return group.matches(registry, meta);
    }

    /** 服务端主线程在起点捕获时使用的 registry 名称解析。 */
    public static String registryName(Block block) {
        Object name = block == null ? null : Block.blockRegistry.getNameForObject(block);
        return name instanceof String ? (String) name : null;
    }
}
