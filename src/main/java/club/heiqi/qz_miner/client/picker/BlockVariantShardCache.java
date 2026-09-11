package club.heiqi.qz_miner.client.picker;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;

/**
 * 变体分片缓存：<b>单 registry 粒度、有界 LRU</b>，缓存 {@link BlockCandidate}（含变体与名称快照）。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §2.5（「变体格分片缓存」上限 LRU 1024 registry、
 * {@code registry} 代际全清、{@code release()} 释放）；{@code team/P2-Miner-Provider-改造设计.md} §2.2/§M2。</p>
 *
 * <p><b>粒度 = 单个 registry</b>（一个方块的全部 meta 变体，ADR A4）：与缓存键天然对齐，
 * 窗口内核不感知粒度，只消费 {@code page(offset, limit)}；单页物化量因此与 {@code limit} 成正比、
 * 与候选总数 N 无关。</p>
 *
 * <p><b>有界</b>：访问序 {@link LinkedHashMap} + {@code removeEldestEntry}，稳态容量严格 ≤ capacity；
 * 淘汰只影响命中率，不影响正确性（重新物化得到同一份候选语义）。</p>
 *
 * <p><b>物化函数可注入</b>：生产 = {@link BlockVariantMaterializer#materialize(String, Block)}；
 * 测试可注入计数桩，直接观测「同一 registry 只物化一次」而不触碰真实多方块 API。</p>
 *
 * <p><b>线程</b>：只在客户端主线程访问（物化触碰多方块 API）；调用方负责入口断言。</p>
 */
public final class BlockVariantShardCache {

    /** 分片容量默认值（ADR §2.5：LRU 1024 registry，真机实测后可微调）。 */
    public static final int DEFAULT_CAPACITY = 1024;

    /** 分片物化函数（registry, block）→ 候选。 */
    public interface Materializer {

        /**
         * @param registry registry 键（非 null）
         * @param block    该 registry 的方块（非 null）
         * @return 候选（非 null）
         */
        BlockCandidate materialize(String registry, Block block);
    }

    private final int capacity;
    private final Materializer materializer;
    private final Map<String, BlockCandidate> shards;

    /** 按默认容量与生产物化器创建空缓存。 */
    public BlockVariantShardCache() {
        this(DEFAULT_CAPACITY, new Materializer() {
            @Override
            public BlockCandidate materialize(String registry, Block block) {
                return BlockVariantMaterializer.materialize(registry, block);
            }
        });
    }

    /**
     * 可注入容量与物化函数（测试用）。
     *
     * @param capacity     条目上限（正数）
     * @param materializer 物化函数（非 null）
     */
    public BlockVariantShardCache(int capacity, Materializer materializer) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (materializer == null) {
            throw new IllegalArgumentException("materializer must not be null");
        }
        this.capacity = capacity;
        this.materializer = materializer;
        this.shards = new LinkedHashMap<String, BlockCandidate>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BlockCandidate> eldest) {
                return size() > BlockVariantShardCache.this.capacity;
            }
        };
    }

    /**
     * 取分片：命中即返回同一实例；未命中物化一次并入缓存（超限淘汰最久未访问）。
     *
     * @param registry registry 键（非 null）
     * @param block    该 registry 的方块；null（清单已变更/未知键）返回降级占位且不缓存
     * @return 候选（非 null）
     */
    public BlockCandidate candidateFor(String registry, Block block) {
        BlockCandidate hit = shards.get(registry);
        if (hit != null) {
            return hit;
        }
        if (block == null) {
            return BlockVariantMaterializer.placeholder(registry);
        }
        BlockCandidate created = materializer.materialize(registry, block);
        if (created == null) {
            created = BlockVariantMaterializer.placeholder(registry);
        }
        shards.put(registry, created);
        return created;
    }

    /** @return 当前条目数（有界性探针） */
    public int size() {
        return shards.size();
    }

    /** @return 条目上限 */
    public int capacity() {
        return capacity;
    }

    /** 清空全部分片（清单代际变化 / 标签代际变化 / {@code release()} 路径）。 */
    public void clear() {
        shards.clear();
    }
}
