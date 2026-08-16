package club.heiqi.qz_miner.chain.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/** seed 租约刷新与生命周期隔离的无 GL 结构合同。 */
public class ChainPreviewControllerTest {

    @Test
    public void localDestroyLeaseIsEstablishedBeforeLookSamplingWithExactIdentityGates() throws Exception {
        String source = source();
        int tick = source.indexOf("public void onClientTick");
        int lock = source.indexOf("shouldLockCurrentPreview()", tick);
        int sample = source.indexOf("resolveLookHit(minecraft, player, selectedSubMode)", tick);
        String destroy = source.substring(source.indexOf("public void onLocalBlockDestroyed"), tick);

        Assert.assertTrue(lock > tick && sample > lock);
        Assert.assertTrue(destroy.contains("previewState.isActive()"));
        Assert.assertTrue(destroy.contains("previewSeedSnapshot == null"));
        Assert.assertTrue(destroy.contains("world != previewSeedWorld"));
        Assert.assertTrue(destroy.contains("currentTarget.equals(previewSeedSnapshot.getOrigin())"));
        Assert.assertTrue(destroy.contains("currentTarget.getX() != x"));
        Assert.assertTrue(destroy.contains("previewOriginLease.acquire("));
        Assert.assertFalse(destroy.contains("getBlock("));
        Assert.assertFalse(destroy.contains("getBlockMetadata("));
    }

    @Test
    public void localLeaseAndPhaseLockAreMergedAndOnlyFullResetClearsLease() throws Exception {
        String source = source();
        int lockStart = source.indexOf("private boolean shouldLockCurrentPreview()");
        int stopStart = source.indexOf("private void stopPreview()", lockStart);
        String lock = source.substring(lockStart, stopStart);
        int resetStart = source.indexOf("private void resetPreview(boolean clearSeedLease)");
        int nextMethod = source.indexOf("private ChainTarget getCurrentLookTarget", resetStart);
        String reset = source.substring(resetStart, nextMethod);

        Assert.assertTrue(lock.contains("previewOriginLease.shouldLock(phase, generation)"));
        Assert.assertTrue(lock.contains("localOriginLocked || phase == ChainPhase.PLANNING"));
        Assert.assertTrue(reset.contains("if (clearSeedLease)"));
        Assert.assertTrue(reset.substring(reset.indexOf("if (clearSeedLease)")).contains(
                "previewOriginLease.reset()"));
        Assert.assertFalse(reset.substring(0, reset.indexOf("if (clearSeedLease)")).contains(
                "previewOriginLease.reset()"));
    }

    @Test
    public void inventoryFallbackRefreshIsRemovedAndLeaseIsClearedOnStop() throws Exception {
        String source = source();
        Assert.assertFalse(source.contains("AutoToolSwapAction"));
        Assert.assertFalse(source.contains("onToolLayoutVerified"));
        Assert.assertTrue(source.contains("world != previewSeedWorld"));
        Assert.assertTrue(source.contains("previewSeedSnapshot = null"));
        Assert.assertTrue(source.contains("previewSeedWorld = null"));
        Assert.assertTrue(source.contains("previewOriginLease.reset()"));
        Assert.assertTrue(source.contains("previewState.getGeneration() != generation"));
        Assert.assertTrue(source.contains("concreteFace == previewConcreteFace"));
    }

    @Test
    public void worldFaceModeAndSubModeBelongToGenerationIdentity() {
        Object worldA = new Object();
        Object worldB = new Object();
        ChainTarget origin = new ChainTarget(1, 2, 3);
        Assert.assertFalse(ChainPreviewController.shouldRestartPreview(
                worldA, worldA, origin, new ChainTarget(1, 2, 3), 2, 2,
                ChainMode.CHAIN, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_BASE));
        Assert.assertTrue(ChainPreviewController.shouldRestartPreview(
                worldA, worldA, origin, new ChainTarget(1, 2, 3), 2, 3,
                ChainMode.CHAIN, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_BASE));
        Assert.assertTrue(ChainPreviewController.shouldRestartPreview(
                worldA, worldB, origin, new ChainTarget(1, 2, 3), 2, 2,
                ChainMode.CHAIN, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_BASE));
        Assert.assertTrue(ChainPreviewController.shouldRestartPreview(
                worldA, worldA, origin, new ChainTarget(1, 2, 3), 2, 2,
                ChainMode.CHAIN, ChainMode.AREA, ChainSubMode.CHAIN_BASE, ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertTrue(ChainPreviewController.shouldRestartPreview(
                worldA, worldA, origin, new ChainTarget(1, 2, 3), 2, 2,
                ChainMode.CHAIN, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_ORE));
    }

    @Test
    public void gtTraverserOwnsItsOriginAndPlannerRegistrationFailureRetries() throws Exception {
        Assert.assertFalse(ChainPreviewController.shouldProjectOriginBeforeTraversal(
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE));
        Assert.assertTrue(ChainPreviewController.shouldProjectOriginBeforeTraversal(ChainSubMode.CHAIN_BASE));

        String source = source();
        int registration = source.indexOf("registerClientPre(");
        int failure = source.indexOf("catch (RuntimeException failure)", registration);
        Assert.assertTrue(registration >= 0 && failure > registration);
        String recovery = source.substring(failure, source.indexOf("MyMod.LOG.debug", failure));
        Assert.assertTrue(recovery.contains("resetPreview(true)"));
        Assert.assertTrue(recovery.contains("retrying on next tick"));

        int identityStart = source.indexOf("private boolean isPreviewStillValid(");
        int identityEnd = source.indexOf("private boolean shouldLockCurrentPreview()", identityStart);
        String identity = source.substring(identityStart, identityEnd);
        Assert.assertFalse(identity.contains("getSelectedMode()"));
        Assert.assertFalse(identity.contains("getSelectedSubMode()"));

        int lockCheck = source.indexOf("if (shouldLockCurrentPreview())");
        int cuboidStop = source.indexOf("selectedSubMode == ChainSubMode.AREA_CUBOID_CLEAR");
        Assert.assertTrue("frozen preview lock must win over live submode changes", lockCheck < cuboidStop);
    }

    @Test
    public void liquidPreviewUsesSharedInclusiveRayAndOtherModesKeepObjectMouseOver() throws Exception {
        String source = source();
        int selectedSubMode = source.indexOf(
                "ChainSubMode selectedSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();");
        int lookHit = source.indexOf("resolveLookHit(minecraft, player, selectedSubMode)", selectedSubMode);
        int target = source.indexOf("getCurrentLookTarget(lookHit)", lookHit);
        int helper = source.indexOf("private MovingObjectPosition resolveLookHit(", target);
        int nextHelper = source.indexOf("private static int resolveConcreteFace(", helper);
        String helperBody = source.substring(helper, nextHelper);

        Assert.assertTrue(selectedSubMode >= 0 && lookHit > selectedSubMode && target > lookHit);
        Assert.assertTrue(helperBody.contains(
                "selectedSubMode != ChainSubMode.INTERACT_LIQUID_SOURCE"));
        Assert.assertTrue(helperBody.contains("return minecraft.objectMouseOver;"));
        Assert.assertTrue(helperBody.contains(
                "player, minecraft.playerController.getBlockReachDistance(), true"));
        Assert.assertTrue(helperBody.contains("InteractionRayTrace.trace("));
        Assert.assertFalse(helperBody.contains("false);"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/client/ChainPreviewController.java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static int count(String value, String fragment) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            result++;
            offset += fragment.length();
        }
        return result;
    }
}
