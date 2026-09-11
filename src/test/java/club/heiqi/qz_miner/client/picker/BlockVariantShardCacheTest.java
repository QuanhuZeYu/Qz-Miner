package club.heiqi.qz_miner.client.picker;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** 分片缓存：单 registry 粒度、LRU 有界、可清空（设计 N2/N3、ADR §2.5）。 */
public class BlockVariantShardCacheTest {

    @Test
    public void shardMaterializesEachRegistryOnce() {
        CountingMaterializer materializer = new CountingMaterializer();
        BlockVariantShardCache cache = new BlockVariantShardCache(8, materializer);
        Block block = new TestBlock();

        BlockCandidate first = cache.candidateFor("test:block", block);
        BlockCandidate second = cache.candidateFor("test:block", block);
        BlockCandidate third = cache.candidateFor("test:block", block);

        Assert.assertSame(first, second);
        Assert.assertSame(first, third);
        Assert.assertEquals(1, materializer.calls.get());
        Assert.assertEquals(1, cache.size());
    }

    @Test
    public void shardCacheIsBoundedAndEvictable() {
        CountingMaterializer materializer = new CountingMaterializer();
        BlockVariantShardCache cache = new BlockVariantShardCache(4, materializer);
        Block block = new TestBlock();

        for (int i = 0; i < 4 + 6; i++) {
            cache.candidateFor("test:block_" + i, block);
        }

        Assert.assertEquals(4, cache.capacity());
        Assert.assertTrue("稳态容量必须 ≤ 上限", cache.size() <= 4);
        // 最久未访问的条目被淘汰：再取一次会重新物化，但结果语义不变
        int before = materializer.calls.get();
        BlockCandidate reread = cache.candidateFor("test:block_0", block);
        Assert.assertEquals("test:block_0", reread.registry());
        Assert.assertEquals(before + 1, materializer.calls.get());
        Assert.assertTrue(cache.size() <= 4);
    }

    @Test
    public void clearDropsEveryShardAndStaysUsable() {
        CountingMaterializer materializer = new CountingMaterializer();
        BlockVariantShardCache cache = new BlockVariantShardCache(8, materializer);
        Block block = new TestBlock();
        cache.candidateFor("test:block", block);

        cache.clear();

        Assert.assertEquals(0, cache.size());
        BlockCandidate reread = cache.candidateFor("test:block", block);
        Assert.assertEquals("test:block", reread.registry());
        Assert.assertEquals(2, materializer.calls.get());
    }

    @Test
    public void unknownBlockDegradesToPlaceholderWithoutMaterializing() {
        CountingMaterializer materializer = new CountingMaterializer();
        BlockVariantShardCache cache = new BlockVariantShardCache(8, materializer);

        BlockCandidate degraded = cache.candidateFor("test:vanished", null);

        Assert.assertEquals("test:vanished", degraded.registry());
        Assert.assertTrue(degraded.variants().isEmpty());
        Assert.assertEquals(0, materializer.calls.get());
        Assert.assertEquals(0, cache.size());
    }

    /** 具名测试方块（Block(Material) 是 protected 构造）。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }

    /** 计数物化桩：不触碰真实多方块 API，只观测「物化了几个分片」。 */
    private static final class CountingMaterializer implements BlockVariantShardCache.Materializer {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public BlockCandidate materialize(String registry, Block block) {
            calls.incrementAndGet();
            return new BlockCandidate(registry, registry,
                    Collections.<BlockVariant>emptyList(), null);
        }
    }
}
