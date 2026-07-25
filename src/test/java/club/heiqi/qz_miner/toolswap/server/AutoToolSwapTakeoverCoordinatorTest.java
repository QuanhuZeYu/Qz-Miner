package club.heiqi.qz_miner.toolswap.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/** poll 前协调门的幂等、成功与超时合同。 */
public class AutoToolSwapTakeoverCoordinatorTest {

    @Test
    public void lowToolWaitsWithoutResendingAndAppliedTakeoverProceeds() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L));
        Assert.assertEquals(1, fixture.sender.requests.size());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(11L));
        Assert.assertEquals("同一等待门只能下发一次", 1, fixture.sender.requests.size());

        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
        publish(fixture, takeover, 12L);

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(13L));
    }

    @Test
    public void timeoutStopsAndUsableToolProceedsWithoutRequest() {
        Fixture timeout = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, timeout.beforePoll(10L));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET, timeout.beforePoll(15L));

        Fixture usable = fixture();
        usable.inventory.slots[0] = stack("tool:held", "ok", 2);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, usable.beforePoll(10L));
        Assert.assertTrue(usable.sender.requests.isEmpty());
    }

    @Test
    public void emptyHandWaitsWhileCreativeAndAuthorizedUnlimitedToolsProceed() {
        Fixture empty = fixture();
        empty.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, empty.beforePoll(10L));
        Assert.assertEquals(1, empty.sender.requests.size());

        Fixture creative = fixture();
        creative.inventory.creative = true;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, creative.beforePoll(10L));

        Fixture unlimited = fixture();
        unlimited.inventory.slots[0] = stack("tool:unlimited", "stable", Integer.MAX_VALUE);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, unlimited.beforePoll(10L));
        Assert.assertTrue(creative.sender.requests.isEmpty());
        Assert.assertTrue(unlimited.sender.requests.isEmpty());
    }

    @Test
    public void roundZeroUsesOnlyAuthorityAndNeverReadsInventoryOrSendsRequest() {
        Fixture fixture = fixture();
        fixture.inventory.readCount = 0;
        fixture.inventory.contextReadCount = 0;

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(0L, 10L, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.beforePoll(0L, 11L, authority(false)));
        Assert.assertEquals(0, fixture.inventory.readCount);
        Assert.assertEquals(0, fixture.inventory.contextReadCount);
        Assert.assertTrue(fixture.sender.requests.isEmpty());
    }

    @Test
    public void roundZeroAuthorityExceptionsSkipTargetWithoutInventoryOrNetworkAccess() {
        for (int variation = 0; variation < 2; variation++) {
            Fixture fixture = fixture();
            resetInventoryAccess(fixture.inventory);

            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                    fixture.beforePoll(0L, 10L, failingAuthority(variation == 1)));
            assertNoInventoryAccess(fixture.inventory);
            Assert.assertTrue(fixture.sender.requests.isEmpty());
        }
    }

    @Test
    public void roundZeroRetiresStaleIssuedGateAndLateIntentHasNoInventorySideEffect() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        resetInventoryAccess(fixture.inventory);

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(0L, 11L, authority(true)));
        assertNoInventoryAccess(fixture.inventory);

        AutoToolSwapIntent late = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, late, fixture.inventory, 12L).outcome());
        assertNoInventoryAccess(fixture.inventory);
        Assert.assertEquals(0, fixture.inventory.swapCount);
    }

    @Test
    public void invalidCurrentBlockOrMetadataSkipsBeforeInventoryAndRequestBoundaries() {
        Fixture fixture = fixture();
        resetInventoryAccess(fixture.inventory);

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 0, 0, fixture.inventory, 10L, 5, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 1, -1, fixture.inventory, 11L, 5, authority(true)));
        assertNoInventoryAccess(fixture.inventory);
        Assert.assertTrue(fixture.sender.requests.isEmpty());
    }

    @Test
    public void emptyDeclineRequiresExactSafeEmptyFallbackAndLiveAuthority() {
        Fixture allowed = fixture();
        allowed.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                allowed.beforePoll(10L, authority(true)));
        AutoToolSwapTakeoverRequest request = allowed.sender.requests.get(0);
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                allowed.roundId, request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                0, 0, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, publish(allowed, decline, 11L).outcome());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                allowed.beforePoll(12L, authority(true)));

        Fixture denied = declinedEmptyFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                denied.beforePoll(12L, authority(false)));

        Fixture occupied = declinedEmptyFixture();
        occupied.inventory.slots[0] = stack("tool:foreign", "occupied", 10);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                occupied.beforePoll(12L, authority(true)));
    }

    @Test
    public void safelySettledCandidateRejectionsAndNonEmptyDeclineSkipOnlyCurrentTarget() {
        Fixture fingerprint = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fingerprint.beforePoll(10L));
        AutoToolSwapTakeoverRequest fingerprintRequest = fingerprint.sender.requests.get(0);
        AutoToolSwapStackState offered = stack("tool:next", "offered", 20);
        fingerprint.inventory.slots[7] = offered;
        AutoToolSwapIntent stale = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fingerprint.roundId, fingerprintRequest.actionSequence(), AutoToolSwapAction.TAKEOVER,
                0, 7, fingerprint.inventory.slots[0].contentFingerprint(), offered.contentFingerprint());
        fingerprint.inventory.slots[7] = stack("tool:next", "changed", 19);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                publish(fingerprint, stale, 11L).outcome());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fingerprint.beforePoll(12L));

        Fixture lowReserve = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, lowReserve.beforePoll(10L));
        AutoToolSwapTakeoverRequest lowRequest = lowReserve.sender.requests.get(0);
        AutoToolSwapStackState low = stack("tool:next", "low", 1);
        lowReserve.inventory.slots[7] = low;
        AutoToolSwapIntent lowIntent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                lowReserve.roundId, lowRequest.actionSequence(), AutoToolSwapAction.TAKEOVER,
                0, 7, lowReserve.inventory.slots[0].contentFingerprint(), low.contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                publish(lowReserve, lowIntent, 11L).outcome());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                lowReserve.beforePoll(12L));

        Fixture noCandidate = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, noCandidate.beforePoll(10L));
        acceptLatestDecline(noCandidate, 11L);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                noCandidate.beforePoll(12L));
    }

    @Test
    public void keyReleaseAndPhaseCloseOverrideSettledTargetSkipWithSessionStop() {
        for (int variation = 0; variation < 2; variation++) {
            Fixture fixture = fixture();
            Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
            AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
            AutoToolSwapStackState offered = stack("tool:next", "offered", 20);
            fixture.inventory.slots[7] = offered;
            AutoToolSwapIntent stale = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER,
                    0, 7, fixture.inventory.slots[0].contentFingerprint(), offered.contentFingerprint());
            fixture.inventory.slots[7] = stack("tool:next", "changed", 19);
            Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                    publish(fixture, stale, 11L).outcome());

            if (variation == 0) {
                fixture.service.onKeyReleased(fixture.player, fixture.endpoint);
            } else {
                fixture.service.observeChainPhase(
                        fixture.player, fixture.endpoint, fixture.roundId, false, true);
            }
            resetInventoryAccess(fixture.inventory);

            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.STOP, fixture.beforePoll(12L));
            assertNoInventoryAccess(fixture.inventory);
        }
    }

    @Test
    public void waitingTargetInvalidationBurnsRequestIdAndSkipsOnlyCurrentTarget() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
        AutoToolSwapTakeoverRequest first = fixture.sender.requests.get(0);

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 0, 0, fixture.inventory, 11L, 5, authority(true)));
        Assert.assertEquals(1, fixture.sender.requests.size());

        AutoToolSwapStackState refreshedAnchor = stack("tool:held-refreshed", "new-content", 1);
        fixture.inventory.slots[0] = refreshedAnchor;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        2, 64, 2, 2, 0, fixture.inventory, 12L, 5, authority(true)));
        AutoToolSwapTakeoverRequest second = fixture.sender.requests.get(1);
        Assert.assertTrue("下一目标必须使用新的独立 request ID",
                second.takeoverRequestId() > first.takeoverRequestId());
        Assert.assertEquals(2, second.targetX());
        Assert.assertEquals(2, second.targetBlockId());

        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, second.takeoverRequestId(), AutoToolSwapAction.TAKEOVER, 0, 7,
                refreshedAnchor.contentFingerprint(), candidate.contentFingerprint());
        Assert.assertEquals("新等待门必须使用下一目标时重新读取的锚点库存",
                AutoToolSwapResultCode.APPLIED, publish(fixture, takeover, 13L).outcome());
    }

    @Test
    public void nonHarvestingUsableHandRequestsCandidateAndAppliedToolIsRevalidated() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = stack("tool:held", "enough", 20);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, authority(false)));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED,
                publish(fixture, takeover, 11L).outcome());
        Assert.assertEquals("错误候选换入后必须在 poll 前只跳过当前目标",
                AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.beforePoll(12L, authority(false)));
    }

    @Test
    public void authorityRuntimeAndLinkageFailuresAreFailClosed() {
        Fixture runtime = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                runtime.beforePoll(10L, failingAuthority(false)));
        Fixture linkage = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                linkage.beforePoll(10L, failingAuthority(true)));
        Assert.assertTrue(runtime.sender.requests.isEmpty());
        Assert.assertTrue(linkage.sender.requests.isEmpty());
    }

    @Test
    public void unsafeInventoryAndReadFailuresSkipOnlyCurrentTargetEvenForCreativeOrEmptyHands() {
        for (int variation = 0; variation < 6; variation++) {
            Fixture fixture = fixture();
            fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
            fixture.inventory.creative = true;
            if (variation == 0) fixture.inventory.alive = false;
            if (variation == 1) fixture.inventory.personalWindow = false;
            if (variation == 2) fixture.inventory.cursorEmpty = false;
            if (variation == 3) fixture.inventory.returnNull = true;
            if (variation == 4) fixture.inventory.readFailure = new IllegalStateException("read");
            if (variation == 5) fixture.inventory.readLinkageFailure = true;
            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET, fixture.beforePoll(10L));
            Assert.assertTrue(fixture.sender.requests.isEmpty());
        }
    }

    @Test
    public void sessionIdentityDriftStopsWhileSettledBlockDriftSkipsOnlyTarget() {
        for (int variation = 0; variation < 8; variation++) {
            assertIdentityDriftStops(variation, false);
            assertIdentityDriftStops(variation, true);
        }
    }

    @Test
    public void sendFailureClosesPendingSoLateTakeoverIsRejected() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        service.observeChainPhase(player, endpoint, roundId, true, false);
        FakeInventory inventory = new FakeInventory();
        inventory.slots[0] = stack("tool:held", "low", 1);
        inventory.slots[7] = stack("tool:next", "fresh", 20);
        AutoToolSwapTakeoverCoordinator coordinator = new AutoToolSwapTakeoverCoordinator(service,
                new AutoToolSwapTakeoverCoordinator.RequestSender() {
                    @Override
                    public void send(Object ignoredEndpoint, AutoToolSwapTakeoverRequest ignoredRequest) {
                        throw new IllegalStateException("send failed");
                    }
                });

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                coordinator.beforePoll(player, endpoint, roundId, 3, 1, 64, 2,
                        1, 0, inventory, 10L, 5));
        AutoToolSwapIntent late = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, 1L, AutoToolSwapAction.TAKEOVER, 0, 7,
                inventory.slots[0].contentFingerprint(), inventory.slots[7].contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.handleIntent(player, endpoint, late, inventory, 11L).outcome());
        Assert.assertEquals(0, inventory.swapCount);
    }

    @Test
    public void committedPublicationPendingKeepsIssuedOwnershipAcrossDriftDeadlineAndRoundClose() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.takeoverRequestId(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());

        AutoToolSwapRoundResult pending = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                takeover, fixture.inventory, 11L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, pending.outcome());
        Assert.assertTrue(fixture.service.snapshot(fixture.player, fixture.endpoint)
                .hasPendingResultPublication());
        Assert.assertEquals(1, fixture.inventory.swapCount);

        Assert.assertEquals("committed publication 未确认时目标漂移只能等待",
                AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 2, 0, fixture.inventory, 20L, 5, authority(true)));
        Assert.assertEquals("deadline 不得抢占已提交 publication",
                AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(21L));
        fixture.service.onKeyReleased(fixture.player, fixture.endpoint);
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId,
                false, true);
        Assert.assertEquals("release/IDLE 只收口 round，不得删除 committed ownership",
                AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(22L));
        Assert.assertEquals(1, fixture.sender.requests.size());
        Assert.assertEquals(1, fixture.inventory.swapCount);

        Assert.assertTrue(fixture.service.confirmIntentResultPublication(fixture.player,
                fixture.endpoint, takeover, pending));
        Assert.assertEquals("publication 确认后漂移目标才可安全局部退休",
                AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 2, 0, fixture.inventory, 23L, 5, authority(true)));
        Assert.assertEquals(1, fixture.inventory.swapCount);
    }

    @Test
    public void stableEmptyHandLeaseSendsOneRequestAndRechecksAuthorityForEveryCoordinate() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
        final int[] authorityCalls = new int[1];
        AutoToolSwapTakeoverCoordinator.HarvestAuthority countingAuthority =
                new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
                    @Override public boolean canHarvest() { authorityCalls[0]++; return true; }
                };

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, countingAuthority));
        acceptLatestDecline(fixture, 11L);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(12L, countingAuthority));
        for (int x = 2; x <= 8; x++) {
            Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                    fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                            x, 64, 2, 1, 0, fixture.inventory, 12L + x, 5, countingAuthority));
        }

        Assert.assertEquals("稳定 block/meta 的空手批次只允许首次真实候选请求", 1,
                fixture.sender.requests.size());
        Assert.assertEquals("DECLINE 安装与每个目标命中都必须实时复验权威", 8, authorityCalls[0]);
    }

    @Test
    public void leaseAuthorityFalseInvalidatesAndRequiresFreshNegotiationForSameKey() {
        Fixture fixture = installedLeaseFixture();

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.beforePoll(20L, authority(false)));
        Assert.assertEquals("权威失败的当前目标不得消费队首或重复发送", 1,
                fixture.sender.requests.size());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(21L, authority(true)));
        Assert.assertEquals("同 key 恢复后必须重新发起 TAKEOVER 协商", 2,
                fixture.sender.requests.size());
    }

    @Test
    public void leaseAuthorityExceptionsInvalidateAndRequireFreshNegotiationForSameKey() {
        for (int variation = 0; variation < 2; variation++) {
            Fixture fixture = installedLeaseFixture();

            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                    fixture.beforePoll(20L, failingAuthority(variation == 1)));
            Assert.assertEquals("异常失败不得消费队首或重复发送", 1,
                    fixture.sender.requests.size());
            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                    fixture.beforePoll(21L, authority(true)));
            Assert.assertEquals("异常恢复后必须重新发起 TAKEOVER 协商", 2,
                    fixture.sender.requests.size());
        }
    }

    @Test
    public void reentrantAuthorityFailureCannotClearTheNewRoundLease() {
        for (int variation = 0; variation < 3; variation++) {
            final Fixture fixture = installedLeaseFixture();
            final int failureVariation = variation;
            AutoToolSwapTakeoverCoordinator.HarvestAuthority reentrantAuthority =
                    new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
                @Override
                public boolean canHarvest() {
                    fixture.service.cleanup(fixture.player);
                    fixture.service.beginRound(fixture.player, fixture.endpoint, 99L, 30L);
                    long newRoundId = fixture.service.activatePendingRound(
                            fixture.player, fixture.endpoint, 31L).serverRoundId();
                    fixture.service.observeChainPhase(fixture.player, fixture.endpoint, newRoundId,
                            true, false);
                    Assert.assertTrue(fixture.service.installEmptyHandFallbackLease(fixture.player,
                            fixture.endpoint, newRoundId, 4,
                            AutoToolSwapRoundService.TargetCapabilityKey.of(1, 0), 0,
                            fixture.inventory.readInventoryIdentity()));
                    if (failureVariation == 1) throw new IllegalStateException("reentrant authority");
                    if (failureVariation == 2) throw new LinkageError("reentrant authority");
                    return false;
                }
            };

            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                    fixture.beforePoll(20L, reentrantAuthority));
            Assert.assertTrue("旧 authority 回调不得清除重入安装的新租约 variation=" + variation,
                    fixture.service.hasEmptyHandFallbackLease(fixture.player));
        }
    }

    @Test
    public void leaseInvalidatesOnTargetInventoryAnchorGuiCursorGenerationAndRoundChanges() {
        Fixture target = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                target.coordinator.beforePoll(target.player, target.endpoint, target.roundId, 3,
                        1, 64, 2, 2, 0, target.inventory, 20L, 5, authority(true)));
        Assert.assertEquals(2, target.sender.requests.size());

        Fixture metadata = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                metadata.coordinator.beforePoll(metadata.player, metadata.endpoint, metadata.roundId, 3,
                        1, 64, 2, 1, 24902, metadata.inventory, 20L, 5, authority(true)));

        Fixture inventory = installedLeaseFixture();
        inventory.inventory.slots[35] = stack("tool:joined", "nbt-count-damage-changed", 20);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                inventory.beforePoll(20L, authority(true)));

        Fixture anchor = installedLeaseFixture();
        anchor.inventory.selectedSlot = 1;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                anchor.beforePoll(20L, authority(true)));

        Fixture gui = installedLeaseFixture();
        gui.inventory.personalWindow = false;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                gui.beforePoll(20L, authority(true)));
        gui.inventory.personalWindow = true;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                gui.beforePoll(21L, authority(true)));

        Fixture cursor = installedLeaseFixture();
        cursor.inventory.cursorEmpty = false;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                cursor.beforePoll(20L, authority(true)));
        cursor.inventory.cursorEmpty = true;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                cursor.beforePoll(21L, authority(true)));

        Fixture generation = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                generation.coordinator.beforePoll(generation.player, generation.endpoint, generation.roundId, 4,
                        1, 64, 2, 1, 0, generation.inventory, 20L, 5, authority(true)));

        Fixture round = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                round.beforePoll(round.roundId + 1L, 20L, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                round.beforePoll(21L, authority(true)));

        Fixture endpoint = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                endpoint.coordinator.beforePoll(endpoint.player, new Object(), endpoint.roundId, 3,
                        1, 64, 2, 1, 0, endpoint.inventory, 20L, 5, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                endpoint.beforePoll(21L, authority(true)));
    }

    @Test
    public void targetAtoBtoARequiresFreshNegotiationAndLeaseAuthorityFailureSkipsTarget() {
        Fixture fixture = installedLeaseFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.beforePoll(20L, authority(false)));
        Assert.assertEquals(1, fixture.sender.requests.size());

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 2, 0, fixture.inventory, 21L, 5, authority(true)));
        acceptLatestDecline(fixture, 22L);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.coordinator.beforePoll(fixture.player, fixture.endpoint, fixture.roundId, 3,
                        1, 64, 2, 2, 0, fixture.inventory, 23L, 5, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(24L, authority(true)));
        Assert.assertEquals("A→B→A 必须分别重新协商", 3, fixture.sender.requests.size());
    }

    @Test
    public void inventoryIdentityFailureInvalidatesLeaseAndFailsClosed() {
        Fixture fixture = installedLeaseFixture();
        fixture.inventory.identityFailure = new IllegalStateException("identity");
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET,
                fixture.beforePoll(20L, authority(true)));
        fixture.inventory.identityFailure = null;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(21L, authority(true)));
        Assert.assertEquals(2, fixture.sender.requests.size());
    }

    private static void assertIdentityDriftStops(int variation, boolean applyFirst) {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        if (applyFirst) {
            AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
            fixture.inventory.slots[7] = candidate;
            AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                    fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
            Assert.assertEquals(AutoToolSwapResultCode.APPLIED,
                    publish(fixture, takeover, 11L).outcome());
        }

        Object endpoint = variation == 0 ? new Object() : fixture.endpoint;
        long roundId = variation == 1 ? fixture.roundId + 1L : fixture.roundId;
        int generation = variation == 2 ? 4 : 3;
        int targetX = variation == 3 ? 2 : 1;
        int targetY = variation == 4 ? 65 : 64;
        int targetZ = variation == 5 ? 3 : 2;
        int blockId = variation == 6 ? 2 : 1;
        int metadata = variation == 7 ? 1 : 0;

        AutoToolSwapTakeoverCoordinator.GateResult expected =
                AutoToolSwapTakeoverCoordinator.GateResult.SKIP_TARGET;
        Assert.assertEquals("identity variation=" + variation + " applied=" + applyFirst, expected,
                fixture.coordinator.beforePoll(fixture.player, endpoint, roundId, generation,
                        targetX, targetY, targetZ, blockId, metadata, fixture.inventory, 12L, 5));
        Assert.assertEquals(1, fixture.sender.requests.size());
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 12L));
    }

    private static Fixture fixture() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        service.observeChainPhase(player, endpoint, roundId, true, false);
        RecordingSender sender = new RecordingSender();
        FakeInventory inventory = new FakeInventory();
        inventory.slots[0] = stack("tool:held", "low", 1);
        return new Fixture(service, new AutoToolSwapTakeoverCoordinator(service, sender), sender,
                inventory, player, endpoint, roundId);
    }

    private static Fixture declinedEmptyFixture() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, authority(true)));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                0, 0, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED,
                publish(fixture, decline, 11L).outcome());
        return fixture;
    }

    private static Fixture installedLeaseFixture() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, authority(true)));
        acceptLatestDecline(fixture, 11L);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(12L, authority(true)));
        return fixture;
    }

    private static void acceptLatestDecline(Fixture fixture, long serverTick) {
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(fixture.sender.requests.size() - 1);
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                fixture.inventory.selectedSlot, fixture.inventory.selectedSlot, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED,
                publish(fixture, decline, serverTick).outcome());
    }

    private static club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult publish(
            Fixture fixture, AutoToolSwapIntent intent, long serverTick) {
        club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult result = fixture.service.handleIntent(
                fixture.player, fixture.endpoint, intent, fixture.inventory, serverTick);
        AutoToolSwapRoundSnapshot snapshot = fixture.service.snapshot(fixture.player, fixture.endpoint);
        if (result.outcome() != AutoToolSwapResultCode.SYNC_FAILED && snapshot != null
                && snapshot.hasPendingResultPublication()) {
            Assert.assertTrue(fixture.service.confirmIntentResultPublication(fixture.player,
                    fixture.endpoint, intent, result));
        }
        return result;
    }

    private static AutoToolSwapTakeoverCoordinator.HarvestAuthority authority(final boolean result) {
        return new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
            @Override public boolean canHarvest() { return result; }
        };
    }

    private static AutoToolSwapTakeoverCoordinator.HarvestAuthority failingAuthority(final boolean linkage) {
        return new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
            @Override public boolean canHarvest() {
                if (linkage) throw new LinkageError("authority");
                throw new IllegalStateException("authority");
            }
        };
    }

    private static void resetInventoryAccess(FakeInventory inventory) {
        inventory.readCount = 0;
        inventory.identityReadCount = 0;
        inventory.contextReadCount = 0;
        inventory.swapCount = 0;
    }

    private static void assertNoInventoryAccess(FakeInventory inventory) {
        Assert.assertEquals("不得读取库存上下文", 0, inventory.contextReadCount);
        Assert.assertEquals("不得读取库存槽位", 0, inventory.readCount);
        Assert.assertEquals("不得读取完整库存身份", 0, inventory.identityReadCount);
        Assert.assertEquals("不得写入库存", 0, inventory.swapCount);
    }

    private static AutoToolSwapStackState stack(String role, String content, int remaining) {
        return AutoToolSwapStackState.occupied(role,
                AutoToolSwapContentFingerprint.fromContent(role, content), remaining);
    }

    private static final class Fixture {
        private final AutoToolSwapRoundService service;
        private final AutoToolSwapTakeoverCoordinator coordinator;
        private final RecordingSender sender;
        private final FakeInventory inventory;
        private final UUID player;
        private final Object endpoint;
        private final long roundId;

        private Fixture(AutoToolSwapRoundService service, AutoToolSwapTakeoverCoordinator coordinator,
                RecordingSender sender, FakeInventory inventory, UUID player, Object endpoint, long roundId) {
            this.service = service;
            this.coordinator = coordinator;
            this.sender = sender;
            this.inventory = inventory;
            this.player = player;
            this.endpoint = endpoint;
            this.roundId = roundId;
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long tick) {
            return coordinator.beforePoll(player, endpoint, roundId, 3, 1, 64, 2,
                    1, 0, inventory, tick, 5);
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long tick,
                AutoToolSwapTakeoverCoordinator.HarvestAuthority authority) {
            return beforePoll(roundId, tick, authority);
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long requestedRoundId, long tick,
                AutoToolSwapTakeoverCoordinator.HarvestAuthority authority) {
            return coordinator.beforePoll(player, endpoint, requestedRoundId, 3, 1, 64, 2,
                    1, 0, inventory, tick, 5, authority);
        }
    }

    private static final class RecordingSender implements AutoToolSwapTakeoverCoordinator.RequestSender {
        private final List<AutoToolSwapTakeoverRequest> requests =
                new ArrayList<AutoToolSwapTakeoverRequest>();
        @Override public void send(Object endpoint, AutoToolSwapTakeoverRequest request) { requests.add(request); }
    }

    private static final class FakeInventory implements AutoToolSwapInventoryPort {
        private final AutoToolSwapStackState[] slots = new AutoToolSwapStackState[36];
        private int swapCount;
        private boolean alive = true;
        private boolean creative;
        private boolean personalWindow = true;
        private boolean cursorEmpty = true;
        private boolean returnNull;
        private RuntimeException readFailure;
        private boolean readLinkageFailure;
        private RuntimeException identityFailure;
        private int readCount;
        private int identityReadCount;
        private int contextReadCount;
        private int selectedSlot;
        @Override public boolean isPlayerAlive() { contextReadCount++; return alive; }
        @Override public boolean isCreativeMode() { contextReadCount++; return creative; }
        @Override public boolean hasPersonalInventoryWindow0() { contextReadCount++; return personalWindow; }
        @Override public boolean isCursorEmpty() { contextReadCount++; return cursorEmpty; }
        @Override public int selectedHotbarSlot() { contextReadCount++; return selectedSlot; }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) {
            readCount++;
            if (readFailure != null) throw readFailure;
            if (readLinkageFailure) throw new NoClassDefFoundError("read");
            if (returnNull) return null;
            return slots[slot] == null ? AutoToolSwapStackState.empty() : slots[slot];
        }
        @Override public AutoToolSwapRoundService.InventoryFingerprint readInventoryIdentity() {
            identityReadCount++;
            if (identityFailure != null) throw identityFailure;
            AutoToolSwapStackState[] captured = new AutoToolSwapStackState[slots.length];
            for (int slot = 0; slot < slots.length; slot++) {
                captured[slot] = slots[slot] == null ? AutoToolSwapStackState.empty() : slots[slot];
            }
            return AutoToolSwapRoundService.InventoryFingerprint.fromSlots(captured);
        }
        @Override public void swapInventorySlotsAtomically(int anchor, int candidate) {
            swapCount++;
            AutoToolSwapStackState value = slots[anchor]; slots[anchor] = slots[candidate]; slots[candidate] = value;
        }
        @Override public void syncInventoryDifference() {}
    }
}
