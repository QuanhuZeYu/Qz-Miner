package club.heiqi.qz_miner.chain.client.verify;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;
import club.heiqi.qz_miner.chain.client.PreviewInputSnapshot;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T22 版本化输入快照独立契约探针（B1.2 / task-20 API 面）。
 *
 * <p>覆盖任务要求：逐字段触发 / 不触发（target 抖动不算语义变化）、去抖边界（连续稳定窗口）、
 * 跨世界与跨模式必须判为语义变化（调用方据此重置，不得进入去抖窗口）、快照不可变。</p>
 *
 * <p>本探针不复用 owner 用例：独立性来自「九维逐一扰动 + 同值不同实例」的对照设计，
 * 以及 temp/chain-preview/verify/wave4_model.json 中独立推导的去抖边界。
 * {@code PreviewTargetDebounce} 为包私有，本包用反射驱动，只调用不复制其实现。</p>
 */
public class InputSnapshotContractTest {

    private static final ChainMode MODE_A = ChainMode.values()[0];
    private static final ChainMode MODE_B = ChainMode.values()[1];
    private static final ChainSubMode SUB_A = ChainSubMode.values()[0];
    private static final ChainSubMode SUB_B = ChainSubMode.values()[1];

    private static ChainPreviewVisualSettings settings(float minScreenWidthPx) {
        ChainPreviewVisualSettings base = ChainPreviewVisualSettings.current();
        return new ChainPreviewVisualSettings(
            base.getBarThickness(),
            minScreenWidthPx,
            base.getDepthModeId(),
            base.getAnimationId(),
            base.getAnimationPhaseId(),
            base.getFadeModeId(),
            base.getColorSourceId(),
            base.getRenderBackendId(),
            base.getColorPrimary(),
            base.getColorSecondary(),
            base.getColorRemote(),
            base.getColorTruncated(),
            base.getAnimationDurationMs(),
            base.getFadeRefreshDistance(),
            base.getFadeFallbackMs(),
            base.getAlphaFadeStartRadius(),
            base.getAlphaFadeEndRadius(),
            base.getAlphaStartValue(),
            base.getAlphaEndValue(),
            base.getLodId(),
            base.getLodMinAlpha(),
            base.isTruncationSignalEnabled(),
            base.getMaxTargetsHardCap());
    }

    private static PreviewInputSnapshot snapshot(
            Object world, ChainTarget target, int face,
            ChainMode mode, ChainSubMode subMode, int radius, int maxTargets,
            ChainPreviewVisualSettings settings, long configRevision, long objectGroupRevision) {
        return new PreviewInputSnapshot(
            world, target, face, mode, subMode, radius, maxTargets,
            settings, configRevision, objectGroupRevision);
    }

    @Test
    public void eachSemanticDimensionAloneForcesChange() {
        Object world = new Object();
        ChainTarget target = new ChainTarget(1, 2, 3);
        ChainPreviewVisualSettings settings = settings(0.0F);
        PreviewInputSnapshot baseline = snapshot(
            world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L);

        // 参照：同值不同实例必须判为「无变化」。
        ChainPreviewVisualSettings sameContent = settings(0.0F);
        Assert.assertNotSame(settings, sameContent);
        PreviewInputSnapshot identical = snapshot(
            world, new ChainTarget(1, 2, 3), 2, MODE_A, SUB_A, 8, 1024, sameContent, 11L, 5L);
        Assert.assertFalse("同值即无变化", baseline.differsInSemanticIdentity(identical));
        Assert.assertTrue(baseline.hasSameTarget(identical));
        Assert.assertEquals(baseline, identical);
        Assert.assertEquals(baseline.hashCode(), identical.hashCode());

        String[] labels = {
            "world", "face", "mode", "subMode", "radius", "maxTargets",
            "visualSettings", "configRevision", "objectGroupRevision"
        };
        PreviewInputSnapshot[] changed = {
            snapshot(new Object(), target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L),
            snapshot(world, target, 3, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L),
            snapshot(world, target, 2, MODE_B, SUB_A, 8, 1024, settings, 11L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_B, 8, 1024, settings, 11L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_A, 9, 1024, settings, 11L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_A, 8, 1025, settings, 11L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_A, 8, 1024, settings(1.0F), 11L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 12L, 5L),
            snapshot(world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 6L)
        };
        Assert.assertEquals(labels.length, changed.length);
        for (int index = 0; index < changed.length; index++) {
            Assert.assertTrue(
                labels[index] + " 变化必须判为语义变化",
                baseline.differsInSemanticIdentity(changed[index]));
            Assert.assertFalse(
                labels[index] + " 变化必须不相等",
                baseline.equals(changed[index]));
        }
    }

    @Test
    public void targetOnlyChangeIsNotSemantic() {
        Object world = new Object();
        ChainPreviewVisualSettings settings = settings(0.0F);
        PreviewInputSnapshot first = snapshot(
            world, new ChainTarget(0, 64, 0), 1, MODE_A, SUB_A, 6, 512, settings, 3L, 4L);
        PreviewInputSnapshot moved = snapshot(
            world, new ChainTarget(0, 65, 0), 1, MODE_A, SUB_A, 6, 512, settings, 3L, 4L);
        Assert.assertFalse("target 抖动不得走语义失效（应走去抖窗口）", first.differsInSemanticIdentity(moved));
        Assert.assertFalse(first.hasSameTarget(moved));
        Assert.assertFalse(first.equals(moved));
    }

    @Test
    public void nullInputsAreSafeAndWorldUsesReferenceIdentity() {
        ChainPreviewVisualSettings settings = settings(0.0F);
        PreviewInputSnapshot first = snapshot(
            new Object(), null, 0, MODE_A, SUB_A, 1, 1, settings, 0L, 0L);
        PreviewInputSnapshot second = snapshot(
            new Object(), null, 0, MODE_A, SUB_A, 1, 1, settings, 0L, 0L);
        Assert.assertTrue("两侧 target 均为 null 时必须视为相同目标", first.hasSameTarget(second));
        Assert.assertTrue("不同 world 实例必须判为语义变化", first.differsInSemanticIdentity(second));
        Assert.assertFalse(first.equals(second));
        Assert.assertTrue("null 候选必须保守判为变化", first.differsInSemanticIdentity(null));
        Assert.assertFalse("null 候选不得视为同目标", first.hasSameTarget(null));

        PreviewInputSnapshot nullSettings = snapshot(
            new Object(), null, 0, MODE_A, SUB_A, 1, 1, null, 0L, 0L);
        PreviewInputSnapshot nullSettings2 = snapshot(
            nullSettings.getWorld(), null, 0, MODE_A, SUB_A, 1, 1, null, 0L, 0L);
        Assert.assertFalse(nullSettings2.differsInSemanticIdentity(nullSettings));
        Assert.assertTrue(nullSettings2.equals(nullSettings));
    }

    @Test
    public void shouldAcceptFollowsStableWindowBoundary() throws Exception {
        Object debounce = newDebounce();
        ChainTarget a = new ChainTarget(10, 20, 30);
        ChainTarget b = new ChainTarget(11, 20, 30);
        Assert.assertFalse("首个 tick 不得接受（required=2）", shouldAccept(debounce, a, 2));
        Assert.assertTrue("第二个稳定 tick 必须接受", shouldAccept(debounce, a, 2));
        Assert.assertEquals(2, stableTicks(debounce));
        Assert.assertTrue("稳定后继续保持接受", shouldAccept(debounce, a, 2));

        Object jitter = newDebounce();
        Assert.assertFalse(shouldAccept(jitter, a, 2));
        Assert.assertTrue(shouldAccept(jitter, a, 2));
        Assert.assertFalse("目标跳变必须重新计数", shouldAccept(jitter, b, 2));
        Assert.assertEquals(1, stableTicks(jitter));
        Assert.assertTrue(shouldAccept(jitter, b, 2));

        Object immediate = newDebounce();
        Assert.assertTrue("required<=1 必须立即接受", shouldAccept(immediate, a, 1));
        Assert.assertTrue("required<=0 必须立即接受", shouldAccept(newDebounce(), a, 0));
        Assert.assertFalse("null 目标不得接受", shouldAccept(newDebounce(), null, 2));
        Assert.assertNull("null 目标必须清空候选", candidate(newDebounce(), null, 2));
    }

    @Test
    public void resetClearsCandidateAndWindow() throws Exception {
        Object debounce = newDebounce();
        ChainTarget a = new ChainTarget(1, 1, 1);
        Assert.assertFalse(shouldAccept(debounce, a, 3));
        Assert.assertEquals(a, candidate(debounce, a, 3));
        debounceReset(debounce);
        Assert.assertNull(candidateOf(debounce));
        Assert.assertEquals(0, stableTicks(debounce));
        Assert.assertFalse("reset 后必须重新累积", shouldAccept(debounce, a, 3));
        Assert.assertFalse(shouldAccept(debounce, a, 3));
        Assert.assertTrue(shouldAccept(debounce, a, 3));
    }

    @Test
    public void snapshotIsImmutableValueObject() {
        Field[] fields = PreviewInputSnapshot.class.getDeclaredFields();
        Assert.assertTrue("字段数必须固定", fields.length >= 10 && fields.length <= 16);
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            Assert.assertTrue("快照字段必须 final: " + field.getName(), Modifier.isFinal(modifiers));
            Assert.assertTrue("快照字段必须 private: " + field.getName(), Modifier.isPrivate(modifiers));
            Assert.assertFalse("快照字段必须非静态: " + field.getName(), Modifier.isStatic(modifiers));
        }
        boolean hashOverridden = false;
        boolean equalsOverridden = false;
        for (Method method : PreviewInputSnapshot.class.getDeclaredMethods()) {
            hashOverridden |= "hashCode".equals(method.getName());
            equalsOverridden |= "equals".equals(method.getName());
        }
        Assert.assertTrue("快照必须实现 hashCode（配置 revision 变化要能判等）", hashOverridden);
        Assert.assertTrue("快照必须实现 equals", equalsOverridden);
    }

    @Test
    public void liveRevisionSourcesAreCallableHeadless() {
        // 配置 epoch：仓库既有口径为 bootstrap 前 fail-fast（ConfigDeadApiTest 同口径），
        // 因此这里只锁定「已提交则非负 / 未提交则按文档快速失败」两种合法形态。
        try {
            long configRevision = PreviewInputSnapshot.currentConfigRevision();
            Assert.assertTrue("config revision 不得为负: " + configRevision, configRevision >= 0L);
        } catch (IllegalStateException failFastBeforeBootstrap) {
            Assert.assertNotNull(failFastBeforeBootstrap.getMessage());
        }
        long objectGroupRevision = PreviewInputSnapshot.currentObjectGroupRevision();
        Assert.assertTrue("objectGroup revision 不得为负: " + objectGroupRevision, objectGroupRevision >= 0L);
    }

    @Test
    public void semanticFastPathAgreesWithSnapshotObject() {
        Object world = new Object();
        ChainPreviewVisualSettings settings = settings(0.0F);
        ChainTarget target = new ChainTarget(1, 2, 3);
        PreviewInputSnapshot current = snapshot(
            world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L);

        Assert.assertFalse("同值必须判无变化", PreviewInputSnapshot.semanticIdentityDiffers(
            current, world, 2, MODE_A, SUB_A, 8, 1024, settings(0.0F), 11L, 5L));
        Assert.assertTrue("current 为 null 必须判为需要换代", PreviewInputSnapshot.semanticIdentityDiffers(
            null, world, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L));

        java.util.List<Object[]> cases = new java.util.ArrayList<Object[]>();
        cases.add(new Object[] {new Object(), 2, MODE_A, SUB_A, 8, 1024, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 3, MODE_A, SUB_A, 8, 1024, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_B, SUB_A, 8, 1024, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_B, 8, 1024, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_A, 9, 1024, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_A, 8, 1025, settings(0.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_A, 8, 1024, settings(1.0F), 11L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_A, 8, 1024, settings(0.0F), 12L, 5L});
        cases.add(new Object[] {world, 2, MODE_A, SUB_A, 8, 1024, settings(0.0F), 11L, 6L});
        for (int index = 0; index < cases.size(); index++) {
            Object[] row = cases.get(index);
            Object rowWorld = row[0];
            int face = ((Integer) row[1]).intValue();
            ChainMode mode = (ChainMode) row[2];
            ChainSubMode subMode = (ChainSubMode) row[3];
            int radius = ((Integer) row[4]).intValue();
            int maxTargets = ((Integer) row[5]).intValue();
            ChainPreviewVisualSettings rowSettings = (ChainPreviewVisualSettings) row[6];
            long configRevision = ((Long) row[7]).longValue();
            long objectGroupRevision = ((Long) row[8]).longValue();
            boolean fastPath = PreviewInputSnapshot.semanticIdentityDiffers(
                current, rowWorld, face, mode, subMode, radius, maxTargets, rowSettings,
                configRevision, objectGroupRevision);
            boolean valueObject = current.differsInSemanticIdentity(snapshot(
                rowWorld, target, face, mode, subMode, radius, maxTargets, rowSettings,
                configRevision, objectGroupRevision));
            Assert.assertTrue("零分配快路径与值对象必须在维度 " + index + " 上一致", fastPath);
            Assert.assertTrue("值对象必须在维度 " + index + " 上判定变化", valueObject);
        }

        Assert.assertFalse("同坐标不同实例不得算目标变化", PreviewInputSnapshot.targetDiffers(current, new ChainTarget(1, 2, 3)));
        Assert.assertTrue(PreviewInputSnapshot.targetDiffers(current, new ChainTarget(1, 2, 4)));
        Assert.assertTrue("无当前快照必须算目标变化", PreviewInputSnapshot.targetDiffers(null, target));
        Assert.assertTrue("null 目标必须算变化（走停机）", PreviewInputSnapshot.targetDiffers(current, null));
    }

    @Test
    public void controllerRestartDecisionUsesSnapshotIdentity() throws Exception {
        Class<?> controllerType = Class.forName("club.heiqi.qz_miner.chain.client.ChainPreviewController");
        Field debounceTicks = controllerType.getDeclaredField("TARGET_DEBOUNCE_TICKS");
        debounceTicks.setAccessible(true);
        Assert.assertEquals("目标抖动窗口必须锁定为 2 tick", 2, debounceTicks.getInt(null));

        Method decision = controllerType.getDeclaredMethod(
            "shouldRestartPreview", PreviewInputSnapshot.class, PreviewInputSnapshot.class);
        decision.setAccessible(true);

        Object world = new Object();
        ChainTarget target = new ChainTarget(5, 6, 7);
        ChainPreviewVisualSettings settings = settings(0.0F);
        PreviewInputSnapshot baseline = snapshot(
            world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L);

        Assert.assertTrue("无当前代必须换 generation",
            ((Boolean) decision.invoke(null, null, baseline)).booleanValue());
        Assert.assertFalse("内容全同不得换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, new ChainTarget(5, 6, 7), 2, MODE_A, SUB_A, 8, 1024, settings(0.0F), 11L, 5L)))
                .booleanValue());
        // 契约入口 = 快照相等判定：target 变化即视为需要换代；2 tick 去抖由接线点
        // （updatePreview 先判 semanticIdentityDiffers，再走 PreviewTargetDebounce）保证，
        // 由本类 shouldAcceptFollowsStableWindowBoundary 独立覆盖。
        Assert.assertTrue("target 变化在契约入口必须判为换代",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, new ChainTarget(5, 6, 8), 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L)))
                .booleanValue());
        Assert.assertTrue("next 为 null 必须保守换代",
            ((Boolean) decision.invoke(null, baseline, null)).booleanValue());
        // 跨世界 / 跨模式：即使目标不变也必须立即换代（不得进入去抖）。
        Assert.assertTrue("跨世界必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                new Object(), target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L))).booleanValue());
        Assert.assertTrue("跨模式必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_B, SUB_A, 8, 1024, settings, 11L, 5L))).booleanValue());
        Assert.assertTrue("跨子模式必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_B, 8, 1024, settings, 11L, 5L))).booleanValue());
        Assert.assertTrue("配置 revision 变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 12L, 5L))).booleanValue());
        Assert.assertTrue("对象组 revision 变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_A, 8, 1024, settings, 11L, 6L))).booleanValue());
        Assert.assertTrue("视觉设置内容变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_A, 8, 1024, settings(1.0F), 11L, 5L))).booleanValue());
        Assert.assertTrue("半径变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_A, 9, 1024, settings, 11L, 5L))).booleanValue());
        Assert.assertTrue("上限变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 2, MODE_A, SUB_A, 8, 1025, settings, 11L, 5L))).booleanValue());
        Assert.assertTrue("命中面变化必须立即换 generation",
            ((Boolean) decision.invoke(null, baseline, snapshot(
                world, target, 3, MODE_A, SUB_A, 8, 1024, settings, 11L, 5L))).booleanValue());
    }

    private static Object newDebounce() throws Exception {
        Class<?> type = Class.forName("club.heiqi.qz_miner.chain.client.PreviewTargetDebounce");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static boolean shouldAccept(Object debounce, ChainTarget target, int requiredTicks) throws Exception {
        Method method = debounce.getClass().getDeclaredMethod("shouldAccept", ChainTarget.class, int.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(debounce, target, Integer.valueOf(requiredTicks))).booleanValue();
    }

    private static void debounceReset(Object debounce) throws Exception {
        Method method = debounce.getClass().getDeclaredMethod("reset");
        method.setAccessible(true);
        method.invoke(debounce);
    }

    private static ChainTarget candidate(Object debounce, ChainTarget target, int requiredTicks) throws Exception {
        shouldAccept(debounce, target, requiredTicks);
        return candidateOf(debounce);
    }

    private static ChainTarget candidateOf(Object debounce) throws Exception {
        Method method = debounce.getClass().getDeclaredMethod("getCandidate");
        method.setAccessible(true);
        return (ChainTarget) method.invoke(debounce);
    }

    private static int stableTicks(Object debounce) throws Exception {
        Method method = debounce.getClass().getDeclaredMethod("getStableTicks");
        method.setAccessible(true);
        return ((Integer) method.invoke(debounce)).intValue();
    }
}
