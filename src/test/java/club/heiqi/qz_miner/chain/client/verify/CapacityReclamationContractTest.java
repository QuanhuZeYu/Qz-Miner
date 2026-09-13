package club.heiqi.qz_miner.chain.client.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T35 波次 7 容量边界与回收契约（B4.2 / task-33 的可验证前置面）。
 *
 * <p>判据全部独立：容量边界用「等于上限 / 超过上限」双侧断言；回收用**构造调用序列**后
 * 反射读取会话内部缓存条目数（chronology / knownPositions / positions / positionIndex /
 * cachedSegments / occupancy），要求四条路径（会话结束 / dispose / 切维度 clear / 世代重建）
 * 之后不再持有条目。</p>
 */
public class CapacityReclamationContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float THICKNESS = 0.045F;
    private static final int LIMIT = ChainPreviewMeshBuilder.MAX_RENDER_TARGETS;

    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> copy = new ArrayList<ChainTarget>(chronological);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private static ChainPreviewMesh extend(GenerationSession session, List<ChainTarget> chronological) {
        return session.extend(newestFirst(chronological), null, VISUALS, THICKNESS);
    }

    /**
     * 反射读取会话内部缓存条目总数（Collection.size() 或包装类型 size()）。
     *
     * <p><b>维护约定</b>：这些字段是 B4.1 代级会话的内部结构；B4.2 若公开容量/峰值/回收访问器，
     * 本探针应逐步改为公共 API 断言，只在确无公共入口处保留反射。**内部字段名变更需同步此处**——
     * {@link #CACHE_FIELDS} 命中数会被断言（&gt;=4），字段改名会让用例显式变红而不是静默通过。</p>
     */
    private static final String[] CACHE_FIELDS = {
        "chronology", "knownPositions", "positions", "positionIndex", "cachedSegments", "occupancy"
    };

    /** @return 成功解析到的内部缓存字段数（用于防止字段改名造成假通过）。 */
    private static int cacheFieldHits(Object session) {
        int hits = 0;
        for (String name : CACHE_FIELDS) {
            if (field(session, name) != null) {
                hits++;
            }
        }
        return hits;
    }

    private static int cacheEntries(Object session) {
        int total = 0;
        for (String name : CACHE_FIELDS) {
            Object value = field(session, name);
            if (value == null) {
                continue;
            }
            if (value instanceof java.util.Collection) {
                total += ((java.util.Collection<?>) value).size();
                continue;
            }
            if (value instanceof java.util.Map) {
                total += ((java.util.Map<?, ?>) value).size();
                continue;
            }
            try {
                java.lang.reflect.Method size = value.getClass().getMethod("size");
                size.setAccessible(true);
                total += ((Integer) size.invoke(value)).intValue();
            } catch (ReflectiveOperationException ignored) {
                // 包装类型没有 size()：跳过（不虚构条目数）
            }
        }
        return total;
    }

    private static Object field(Object owner, String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (IllegalAccessException denied) {
                throw new IllegalStateException("无法读取字段 " + name, denied);
            }
        }
        return null;
    }

    @Test
    public void capacityBoundaryIsExactAtUniqueLimit() {
        GenerationSession atLimit = new ChainPreviewMeshBuilder().beginGeneration();
        List<ChainTarget> exactly = VerifyShapes.scatteredX(LIMIT, 3);
        ChainPreviewMesh exactMesh = extend(atLimit, exactly);
        Assert.assertFalse("正好 " + LIMIT + " 个唯一目标不得置截断", exactMesh.isTruncated());
        Assert.assertEquals(LIMIT, exactMesh.getBlockCount());
        Assert.assertEquals(LIMIT, atLimit.getGenerationTargetCount());

        GenerationSession beyond = new ChainPreviewMeshBuilder().beginGeneration();
        List<ChainTarget> over = new ArrayList<ChainTarget>(exactly);
        over.add(new ChainTarget(LIMIT * 3, 0, 0));
        ChainPreviewMesh overMesh = extend(beyond, over);
        Assert.assertTrue("超过上限必须置截断", overMesh.isTruncated());
        Assert.assertEquals("超过上限必须保留上限个唯一目标", LIMIT, overMesh.getBlockCount());
        Assert.assertTrue("累积必须有界: " + beyond.getGenerationTargetCount(),
            beyond.getGenerationTargetCount() <= LIMIT + 1);
    }

    @Test
    public void duplicatesNeverConsumeQuota() {
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        List<ChainTarget> many = new ArrayList<ChainTarget>();
        for (int index = 0; index < LIMIT + 500; index++) {
            many.add(new ChainTarget(7, 64, -3));
        }
        ChainPreviewMesh mesh = extend(session, many);
        Assert.assertFalse("重复目标不得触发截断（去重先于配额）", mesh.isTruncated());
        Assert.assertEquals(1, mesh.getBlockCount());
        Assert.assertEquals(1, session.getGenerationTargetCount());
    }

    @Test
    public void sessionDisposeReleasesIncrementalCaches() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        extend(session, VerifyShapes.plane(4));
        Assert.assertTrue("反射字段名必须仍然存在（内部结构变更需同步本探针）",
            cacheFieldHits(session) >= 4);
        Assert.assertTrue("构建后必须持有缓存条目", cacheEntries(session) > 0);

        session.dispose();
        try {
            extend(session, VerifyShapes.plane(4));
            Assert.fail("dispose 后再次使用必须被拒绝");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        Assert.assertEquals("dispose 回收后缓存条目必须为 0", 0, cacheEntries(session));
        Assert.assertEquals(0, session.getGenerationTargetCount());
        Assert.assertNull(session.getMesh());
        Assert.assertEquals(0, session.getAnchorX());
    }

    @Test
    public void cacheClearAndGenerationChangeReleaseDisposedSessions() {
        ChainPreviewState state = new ChainPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        for (int x = 0; x < 3; x++) {
            state.addPreviewTarget(generation, new ChainTarget(x * 3, 0, 0));
        }
        harness.runUntilPublication(8);
        Assert.assertNotNull(harness.pollPublication());

        Object firstSession = field(harness.cache(), "generationSession");
        Assert.assertNotNull("构建后缓存必须持有代会话（内部字段名变更需同步本探针）", firstSession);
        Assert.assertTrue("代会话必须持有条目", cacheEntries(firstSession) > 0);

        // 回收路径 A：切维度/清理（inactive change -> disposeGenerationSessionLocked）
        state.clear();
        harness.runUntilPublication(8);
        Assert.assertNull("清理后缓存不得再持有代会话", field(harness.cache(), "generationSession"));
        Assert.assertTrue("被释放的会话必须标记 disposed",
            ((Boolean) invoke(firstSession, "isDisposed")).booleanValue());

        // 回收路径 B：世代重建 -> 旧会话释放、新会话新建
        int next = state.begin(new ChainTarget(40, 0, 0));
        state.addPreviewTarget(next, new ChainTarget(40, 0, 0));
        harness.runUntilPublication(8);
        Object nextSession = field(harness.cache(), "generationSession");
        Assert.assertNotNull("新代必须新建代会话", nextSession);
        Assert.assertNotSame("世代重建必须换会话", firstSession, nextSession);
        Assert.assertTrue("新会话必须持有新代条目", cacheEntries(nextSession) > 0);
        Assert.assertNotEquals(next, 0);
    }

    private static Object invoke(Object owner, String method) {
        try {
            java.lang.reflect.Method target = owner.getClass().getMethod(method);
            target.setAccessible(true);
            return target.invoke(owner);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法调用 " + method, failure);
        }
    }
}
