package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/** B1.2：版本化输入快照的值相等契约（每个字段变化都必须换 generation）。 */
public class PreviewInputSnapshotTest {

    private static final ChainPreviewVisualSettings SETTINGS = ChainPreviewVisualSettings.current();

    @Test
    public void equalSnapshotDoesNotRestartAndEveryFieldChangeDoes() {
        Object world = new Object();
        Object otherWorld = new Object();
        ChainTarget target = new ChainTarget(1, 2, 3);

        PreviewInputSnapshot base = snapshot(world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 1L);
        Assert.assertFalse("完全相同（含等价新对象）的快照不得重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, new ChainTarget(1, 2, 3), 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 1L)));
        Assert.assertFalse("同一快照实例不得重建",
            ChainPreviewController.shouldRestartPreview(base, base));

        Assert.assertTrue("world 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(otherWorld, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 1L)));
        PreviewInputSnapshot targetOnlyChanged = snapshot(
            world, new ChainTarget(1, 2, 4), 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 1L);
        Assert.assertTrue("契约入口：target 变化即需要换代",
            ChainPreviewController.shouldRestartPreview(base, targetOnlyChanged));
        Assert.assertFalse("仅 target 变化不属于语义变化（由接线点走去抖窗口）",
            base.differsInSemanticIdentity(targetOnlyChanged));
        Assert.assertTrue("仅 target 变化必须被 targetDiffers 捕获",
            PreviewInputSnapshot.targetDiffers(base, new ChainTarget(1, 2, 4)));
        Assert.assertTrue("next 为 null 保守换代",
            ChainPreviewController.shouldRestartPreview(base, null));
        Assert.assertTrue("命中面变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, target, 3, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 1L)));
        Assert.assertTrue("主模式变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, target, 2, ChainMode.AREA, ChainSubMode.AREA_SAME_BLOCK, SETTINGS, 1L, 1L)));
        Assert.assertTrue("子模式变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_ORE, SETTINGS, 1L, 1L)));

        PreviewInputSnapshot otherRadius = new PreviewInputSnapshot(
            world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, 9, 4096, SETTINGS, 1L, 1L);
        PreviewInputSnapshot otherMaxTargets = new PreviewInputSnapshot(
            world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, 8, 2048, SETTINGS, 1L, 1L);
        Assert.assertTrue("半径变化必须重建",
            ChainPreviewController.shouldRestartPreview(base, otherRadius));
        Assert.assertTrue("上限变化必须重建",
            ChainPreviewController.shouldRestartPreview(base, otherMaxTargets));

        Assert.assertTrue("配置 revision 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 2L, 1L)));
        Assert.assertTrue("对象组 revision 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, snapshot(world, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 1L, 2L)));

        Assert.assertTrue("无当前快照（首 tick）必须立即重建",
            ChainPreviewController.shouldRestartPreview(null, base));
    }

    @Test
    public void settingsContentChangeRestartsWhileRebuiltEqualSnapshotDoesNot() {
        Object world = new Object();
        ChainTarget target = new ChainTarget(5, 5, 5);
        ChainPreviewVisualSettings previousCurrent = ChainPreviewVisualSettings.current();
        ChainPreviewVisualSettings fresh = ChainPreviewVisualSettings.fromConfig();
        ChainPreviewVisualSettings equalCopy = ChainPreviewVisualSettings.fromConfig();
        try {
            ChainPreviewVisualSettings.publish(fresh);
            Assert.assertNotSame("fromConfig 每次返回新实例", fresh, equalCopy);
            Assert.assertEquals("同配置两次 fromConfig 内容必须相等（值语义）", fresh, equalCopy);

            PreviewInputSnapshot base = snapshot(
                world, target, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, fresh, 0L, 0L);
            Assert.assertFalse("1 Hz 重建但内容相同不得触发重建", ChainPreviewController.shouldRestartPreview(
                base, snapshot(world, target, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, equalCopy, 0L, 0L)));

            ChainPreviewVisualSettings changed = new ChainPreviewVisualSettings(
                0.1F, 0.0F, "xray", "off", "order", "timer", "builtin", "auto",
                0x40E6FF, 0x5CE1A6, 0xFFC857, 0xB08CFF, 0x8FA9D0, 0xFF7A6B,
                120, 0.5F, 250, 2.0F, 6.0F, 0.78F, 0.15F, "off", 0.05F, false, 4096);
            Assert.assertNotEquals(fresh, changed);
            Assert.assertTrue("视觉设置内容变化必须重建（按住连锁键改配置即重建）",
                ChainPreviewController.shouldRestartPreview(
                    base, snapshot(world, target, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, changed, 0L, 0L)));
        } finally {
            ChainPreviewVisualSettings.publish(previousCurrent);
        }
    }

    @Test
    public void semanticIdentityDiffersIgnoresTargetWhileTargetDiffersIgnoresSemantics() {
        Object world = new Object();
        ChainTarget target = new ChainTarget(1, 1, 1);
        PreviewInputSnapshot base = snapshot(world, target, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, SETTINGS, 3L, 4L);

        Assert.assertFalse("无分配语义比较：target 不同不算语义变化",
            PreviewInputSnapshot.semanticIdentityDiffers(
                base, world, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, 8, 4096, SETTINGS, 3L, 4L));
        Assert.assertTrue("无分配语义比较：revision 变化算语义变化",
            PreviewInputSnapshot.semanticIdentityDiffers(
                base, world, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, 8, 4096, SETTINGS, 3L, 5L));
        Assert.assertTrue("无分配语义比较：current 为 null 必须视为变化",
            PreviewInputSnapshot.semanticIdentityDiffers(
                null, world, 0, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, 8, 4096, SETTINGS, 3L, 4L));

        Assert.assertTrue("无分配 target 比较",
            PreviewInputSnapshot.targetDiffers(base, new ChainTarget(2, 1, 1)));
        Assert.assertFalse("无分配 target 比较：同值不变化",
            PreviewInputSnapshot.targetDiffers(base, new ChainTarget(1, 1, 1)));
        Assert.assertTrue("无分配 target 比较：current 为 null 视为变化",
            PreviewInputSnapshot.targetDiffers(null, target));
    }

    private static PreviewInputSnapshot snapshot(
            Object world, ChainTarget target, int face, ChainMode mode, ChainSubMode subMode,
            ChainPreviewVisualSettings settings, long configRevision, long objectGroupRevision) {
        return new PreviewInputSnapshot(
            world, target, face, mode, subMode, 8, 4096, settings, configRevision, objectGroupRevision);
    }
}
