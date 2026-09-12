package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.PickerSourceVersion;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.field.PickerSourceGuard;

/**
 * 惰性候选源 SPI 契约测试（设计 N5/N6/N7/N8/N11/N12/N13 + ADR A-02/A-03/A-04/A-08/Z-4）。
 *
 * <p>全部经假清单 + 计数物化桩驱动：不读真实 Block registry（headless JVM 下为空），
 * 不触碰多方块 API，物化次数即「getSubBlocks 等价工作量」的观测面。</p>
 */
public class BlockPickerCandidateSourceTest {

    @Test
    public void constructorAllocatesNoCandidateObjects() {
        Fixture fixture = new Fixture(4, 8);

        Assert.assertEquals("构造期不得捕获清单", 0, fixture.snapshotSource.calls);
        Assert.assertEquals("构造期不得物化任何分片", 0, fixture.materializer.calls);
        Assert.assertEquals("构造期 revision 必须为 0", 0L, fixture.source.registryRevision());
        Assert.assertTrue(fixture.source.isRegistryDirty());
        Assert.assertEquals(PickerSourceVersion.initial(), fixture.source.version());
    }

    @Test
    public void browsePageIsStableIdentitySliceAndSplitsEqualWholeTake() {
        Fixture fixture = new Fixture(10, 4);
        PickerQuery browse = PickerQuery.browse(0, null);

        Assert.assertEquals(10, fixture.source.matchCount(browse));
        List<SearchPickerData.Candidate> whole = fixture.source.page(browse, 0, 10);
        List<String> wholeKeys = candidateKeys(whole);
        Assert.assertEquals("浏览顺序 = 清单（注册）序", fixture.registries, wholeKeys);
        Assert.assertEquals("同一代际内 key 不得重复", 10, new LinkedHashSet<String>(wholeKeys).size());

        Assert.assertEquals(wholeKeys.subList(0, 2), candidateKeys(fixture.source.page(browse, 0, 2)));
        Assert.assertEquals(wholeKeys.subList(2, 10), candidateKeys(fixture.source.page(browse, 2, 8)));
        Assert.assertEquals(wholeKeys.subList(8, 10), candidateKeys(fixture.source.page(browse, 8, 5)));
        Assert.assertEquals("重复调用必须逐项相等", wholeKeys, candidateKeys(fixture.source.page(browse, 0, 10)));
        Assert.assertTrue(fixture.source.page(browse, 10, 5).isEmpty());
        Assert.assertTrue(fixture.source.page(browse, 0, 0).isEmpty());
        try {
            fixture.source.page(browse, 0, -1);
            Assert.fail("负 limit 必须立即失败");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void searchOnlyMaterializesRequestedWindow() {
        Fixture fixture = new Fixture(100, 4);
        PickerQuery query = PickerQuery.text("Block", 0, null);

        Assert.assertEquals("文本 lane 命中数为真值", 100, fixture.source.matchCount(query));
        Assert.assertTrue("名字索引构建需要一次性全量名字", fixture.materializer.calls >= 100);

        int afterBuild = fixture.materializer.calls;
        Assert.assertEquals(8, fixture.source.page(query, 0, 8).size());
        Assert.assertTrue("窗口物化量必须 ∝ limit（与 N 无关）",
                fixture.materializer.calls - afterBuild <= 8);

        int afterWindow = fixture.materializer.calls;
        fixture.source.page(query, 0, 8);
        Assert.assertTrue("同一 query 重复取窗口不得放大物化量",
                fixture.materializer.calls - afterWindow <= 8);

        Fixture doubled = new Fixture(200, 4);
        doubled.source.matchCount(query);
        int doubledAfterBuild = doubled.materializer.calls;
        doubled.source.page(query, 0, 8);
        Assert.assertTrue("N 翻倍后同一 limit 的物化量不变",
                doubled.materializer.calls - doubledAfterBuild <= 8);
    }

    /**
     * A 方案回归（候选源级）：搜索 lane 命中序无窗口上限，任意大 offset 都按全局命中序切片 ——
     * {@code page(query, 70, 20)} 必须与 {@code page(query, 0, 90)} 的第 70..89 项逐项相等
     * （旧面板层把搜索 lane 总量夹到 64，第 65 项起不可达）。
     */
    @Test
    public void searchPageSlicesFarOffsetInGlobalHitOrder() {
        Fixture fixture = new Fixture(200, 8);
        PickerQuery query = PickerQuery.text("Block", 0, null);
        Assert.assertEquals("命中数为真值", 200, fixture.source.matchCount(query));

        List<String> whole = candidateKeys(fixture.source.page(query, 0, 90));
        Assert.assertEquals(90, whole.size());
        List<String> late = candidateKeys(fixture.source.page(query, 70, 20));

        Assert.assertEquals("offset 70 起必须取到全局第 70..89 项", whole.subList(70, 90), late);
        Assert.assertEquals(20, late.size());
        Assert.assertEquals("同一 query 重复切片必须逐项稳定", late,
                candidateKeys(fixture.source.page(query, 70, 20)));
    }

    @Test
    public void exactLookupIsConstantTimeAndCached() {
        Fixture fixture = new Fixture(50, 8);

        SearchPickerData.Candidate first = fixture.source.exact("test:block_1");
        Assert.assertNotNull(first);
        Assert.assertEquals("test:block_1", first.key());
        Assert.assertEquals(1, fixture.materializer.calls);

        fixture.source.exact("test:block_1");
        Assert.assertEquals("同一 key 第二次必须命中分片缓存", 1, fixture.materializer.calls);

        Assert.assertNull(fixture.source.exact("test:absent"));
        Assert.assertNull(fixture.source.exact(null));
        Assert.assertEquals(1, fixture.materializer.calls);

        for (int i = 2; i < 10; i++) {
            Assert.assertNotNull(fixture.source.exact("test:block_" + i));
        }
        Assert.assertTrue("k 个唯一 key 的物化次数 ≤ k", fixture.materializer.calls <= 10);
    }

    @Test
    public void languagePushBumpsNameRevisionAndRefreshesLabels() {
        Fixture fixture = new Fixture(3, 8);
        fixture.source.onEnvironmentChanged(5L, 7L);
        Assert.assertEquals("基线推送不得自增 name", 0L, fixture.source.nameRevision());
        Assert.assertEquals("基线推送不得自增 icon", 0L, fixture.source.iconRevision());
        Assert.assertEquals("Block test:block_0", fixture.source.exact("test:block_0").label());

        fixture.materializer.labelPrefix = "方块 ";
        fixture.source.onEnvironmentChanged(6L, 7L);

        Assert.assertEquals(1L, fixture.source.nameRevision());
        Assert.assertEquals("资源代际未变不得自增 icon", 0L, fixture.source.iconRevision());
        Assert.assertEquals("方块 test:block_0", fixture.source.exact("test:block_0").label());
        Assert.assertEquals("标签代际必须丢弃旧分片并重物化", 2, fixture.materializer.calls);
        Assert.assertEquals(1L, fixture.source.version().name());
    }

    @Test
    public void resourcePushBumpsIconRevisionOnly() {
        Fixture fixture = new Fixture(3, 8);
        fixture.source.onEnvironmentChanged(1L, 1L);
        fixture.source.exact("test:block_0");
        int materialized = fixture.materializer.calls;

        long registryBefore = fixture.source.version().registry();
        fixture.source.onEnvironmentChanged(1L, 2L);

        Assert.assertEquals("资源重载不得改 name 段", 0L, fixture.source.nameRevision());
        Assert.assertEquals(1L, fixture.source.iconRevision());
        Assert.assertEquals("图标代际不清分片结构", materialized, fixture.materializer.calls);
        Assert.assertEquals("资源重载不改清单段", registryBefore, fixture.source.version().registry());
    }

    @Test
    public void versionStaysIdenticalWithoutEnvironmentPush() {
        Fixture fixture = new Fixture(3, 8);
        Assert.assertEquals(PickerSourceVersion.initial(), fixture.source.version());

        fixture.source.size();
        PickerSourceVersion afterFirstRead = fixture.source.version();
        Assert.assertEquals(1L, afterFirstRead.registry());

        fixture.source.matchCount(PickerQuery.browse(0, null));
        fixture.source.exact("test:block_0");
        fixture.source.categories(0);
        fixture.source.page(PickerQuery.browse(0, null), 0, 2);
        Assert.assertSame("不推送则 version() 恒等（Z-4）+ 零分配自持快照",
                afterFirstRead, fixture.source.version());
    }

    @Test
    public void degradedCandidateStillEncodable() {
        Fixture fixture = new Fixture(2, 8, true);

        SearchPickerData.Candidate degraded = fixture.source.exact("test:block_0");

        Assert.assertNotNull(degraded);
        Assert.assertEquals("test:block_0", degraded.label());
        Assert.assertTrue(degraded.variants().isEmpty());
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        Assert.assertEquals("test:block_0@*", codec.encodeMember(null, new SearchPickerData.Selection(
                "test:block_0", SearchPickerData.SelectionMode.ALL, Collections.<String>emptyList())));
        Assert.assertEquals("test:block_0@3", codec.encodeMember(null, new SearchPickerData.Selection(
                "test:block_0", SearchPickerData.SelectionMode.SELECTED,
                Collections.singletonList("test:block_0@3"))));
    }

    @Test
    public void registryDirtyDefersRebuildToNextRead() {
        Fixture fixture = new Fixture(5, 8);
        fixture.source.size();
        int captures = fixture.snapshotSource.calls;
        long revision = fixture.source.registryRevision();

        fixture.source.markRegistryDirty("fml_modid_mapping");
        fixture.source.markRegistryDirty("tick_fallback");

        Assert.assertEquals("标脏不得重建", captures, fixture.snapshotSource.calls);
        Assert.assertEquals("标脏不得改变 revision（重建时才 +1）", revision, fixture.source.registryRevision());
        Assert.assertTrue(fixture.source.isRegistryDirty());

        Assert.assertEquals(5, fixture.source.size());
        Assert.assertEquals(captures + 1, fixture.snapshotSource.calls);
        Assert.assertEquals(revision + 1, fixture.source.registryRevision());
        Assert.assertFalse(fixture.source.isRegistryDirty());
    }

    @Test
    public void releaseClearsAllCachesAndStaysUsable() {
        Fixture fixture = new Fixture(10, 16);
        fixture.source.size();
        fixture.source.exact("test:block_0");
        long revision = fixture.source.registryRevision();
        Assert.assertEquals(1, fixture.source.shardCacheSize());

        fixture.source.release();

        Assert.assertEquals(0, fixture.source.shardCacheSize());
        Assert.assertTrue(fixture.source.isRegistryDirty());
        Assert.assertEquals("释放后仍可用", 10, fixture.source.size());
        Assert.assertEquals("版本号不得回绕", revision + 1, fixture.source.registryRevision());
        Assert.assertEquals("test:block_0", fixture.source.exact("test:block_0").key());
    }

    @Test
    public void mainThreadAssertionFailsFastOffThread() {
        final Fixture fixture = new Fixture(3, 8);
        PickerSourceGuard.__installThreadOracleForTests(new PickerSourceGuard.ThreadOracle() {
            @Override
            public boolean isMainThread() {
                return false;
            }

            @Override
            public String describe() {
                return "test-oracle:never-main";
            }
        });
        try {
            fixture.source.page(PickerQuery.browse(0, null), 0, 1);
            Assert.fail("非客户端主线程调用必须 fail-fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue("异常必须指名入口", expected.getMessage().contains("page"));
        }
        try {
            fixture.source.exact("test:block_0");
            Assert.fail("exact 同样必须 fail-fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("exact"));
        } finally {
            PickerSourceGuard.__resetForTests();
        }
        Assert.assertEquals("无判定源时降级放行", 3, fixture.source.size());
    }

    @Test
    public void categoriesExposeSingleModDimensionWithCountsAndArgumentValidation() {
        Fixture fixture = new Fixture(6, 8);

        List<SearchPickerCategories.Category> dim0 = fixture.source.categories(0);

        Assert.assertEquals(Arrays.asList("test"), categoryKeys(dim0));
        Assert.assertEquals(6, SearchPickerCategories.find(dim0, "test").count());
        Assert.assertEquals("test", fixture.source.categories(0).get(0).key());
        Assert.assertTrue(fixture.source.categories(1).isEmpty());
        try {
            fixture.source.categories(-1);
            Assert.fail("负维度必须立即失败");
        } catch (IllegalArgumentException expected) {
        }
        try {
            fixture.source.page(null, 0, 1);
            Assert.fail("null query 必须立即失败");
        } catch (IllegalArgumentException expected) {
        }
        try {
            fixture.source.page(PickerQuery.browse(0, null), -1, 1);
            Assert.fail("负 offset 必须立即失败");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static List<String> candidateKeys(List<SearchPickerData.Candidate> candidates) {
        List<String> keys = new ArrayList<String>();
        for (SearchPickerData.Candidate candidate : candidates) {
            keys.add(candidate.key());
        }
        return keys;
    }

    private static List<String> categoryKeys(List<SearchPickerCategories.Category> categories) {
        List<String> keys = new ArrayList<String>();
        for (SearchPickerCategories.Category category : categories) {
            keys.add(category.key());
        }
        return keys;
    }

    /** 假清单 + 计数物化桩 + 计数捕获桩；不读真实注册表。 */
    private static final class Fixture {
        private final List<String> registries = new ArrayList<String>();
        private final CountingSnapshotSource snapshotSource = new CountingSnapshotSource();
        private final CountingMaterializer materializer = new CountingMaterializer();
        private final BlockPickerCandidateSource source;

        private Fixture(int count, int shardCapacity) {
            this(count, shardCapacity, false);
        }

        private Fixture(int count, int shardCapacity, boolean degraded) {
            Map<String, Block> blocks = new LinkedHashMap<String, Block>();
            for (int i = 0; i < count; i++) {
                String registry = "test:block_" + i;
                registries.add(registry);
                blocks.put(registry, new TestBlock());
            }
            materializer.degraded = degraded;
            snapshotSource.snapshot = BlockRegistrySnapshot.of(blocks);
            source = new BlockPickerCandidateSource(snapshotSource,
                    new BlockVariantShardCache(shardCapacity, materializer));
        }
    }

    /** 计数清单捕获桩（构造期/标脏后未重建时调用数必须为 0）。 */
    private static final class CountingSnapshotSource implements BlockPickerCandidateSource.SnapshotSource {
        private BlockRegistrySnapshot snapshot;
        private int calls;

        @Override
        public BlockRegistrySnapshot capture() {
            calls++;
            return snapshot;
        }
    }

    /** 计数物化桩：可切换标签前缀（语言代际）与降级形态。 */
    private static final class CountingMaterializer implements BlockVariantShardCache.Materializer {
        private int calls;
        private String labelPrefix = "Block ";
        private boolean degraded;

        @Override
        public BlockCandidate materialize(String registry, Block block) {
            calls++;
            if (degraded) {
                return BlockVariantMaterializer.placeholder(registry);
            }
            String label = labelPrefix + registry;
            return new BlockCandidate(registry, label,
                    Collections.singletonList(new BlockVariant(0, label + " (0)", null)), null);
        }
    }

    /** Block(Material) 是 protected 构造。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
