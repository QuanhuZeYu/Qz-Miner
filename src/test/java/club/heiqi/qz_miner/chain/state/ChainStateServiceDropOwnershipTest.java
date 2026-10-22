package club.heiqi.qz_miner.chain.state;

import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.PlayerManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 无玩家、无世界时，移除运行态也必须留下可领取的掉落 owner。 */
public class ChainStateServiceDropOwnershipTest {

    /** 纯 JVM 只验证缺少恢复端点时拒绝消费和重复请求幂等，真实 spawn 仍需实机。 */
    @Test
    public void missingRecoveryEndpointCannotConsumeOrDuplicateSuspendedAssets() {
        UUID playerId = UUID.randomUUID();
        ChainStateService service = new ChainStateService();
        ChainPlayerDropBuffer buffer = service.getOrCreatePlayerState(playerId).getDropBuffer();
        ItemStack drop = new ItemStack(new Item());
        buffer.add(drop);
        service.suspendDropsOnServerStopping();
        service.suspendDropsOnServerStopping();

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                service.releaseSuspendedPlayerDrops(null);
                Assert.fail("无指定玩家时不得假报恢复成功");
            } catch (IllegalArgumentException expected) {
                // 缺少端点不能解析 UUID，更不能触碰任一玩家资产。
            }
            Assert.assertEquals(1, service.getSuspendedPlayerDrops(playerId).size());
            Assert.assertSame(buffer, service.getSuspendedPlayerDrops(playerId).get(0));
            Assert.assertEquals(1, buffer.size());
        }
        List<ItemStack> claimed = buffer.drain();
        Assert.assertEquals(1, claimed.size());
        Assert.assertSame(drop.getItem(), claimed.get(0).getItem());
        Assert.assertTrue("同一buffer重复领取不得再次返回资产", buffer.drain().isEmpty());
        Assert.assertTrue(service.getSuspendedPlayerDrops(playerId).isEmpty());
    }

    @Test
    public void stoppedWorldDropsRemainClaimableWithoutEnteringTheNextWorld() {
        UUID playerId = UUID.randomUUID();
        ChainStateService service = new ChainStateService();
        ChainPlayerDropBuffer oldBuffer = service.getOrCreatePlayerState(playerId).getDropBuffer();
        oldBuffer.add(new ItemStack(new Item()));

        service.suspendDropsOnServerStopping();

        Assert.assertNull(service.getPlayerState(playerId));
        Assert.assertFalse(service.getPlayerStates().iterator().hasNext());
        Assert.assertSame(oldBuffer, service.getSuspendedPlayerDrops(playerId).get(0));
        ChainPlayerState nextWorld = service.getOrCreatePlayerState(playerId);
        Assert.assertNotSame(oldBuffer, nextWorld.getDropBuffer());
        Assert.assertTrue("旧世界未交付资产不能自动混入新世界", nextWorld.getDropBuffer().isEmpty());
        Assert.assertEquals(1, service.getSuspendedPlayerDrops(playerId).get(0).drain().size());
        Assert.assertTrue("领取后释放停止服务端的空owner", service.getSuspendedPlayerDrops(playerId).isEmpty());
    }

    @Test
    public void reconnectStartsFreshRuntimeAndKeepsTheSameDropBuffer() {
        PlayerManager previousManager = MyMod.playerManager;
        MyMod.playerManager = null;
        try {
            UUID playerId = UUID.randomUUID();
            ChainStateService service = new ChainStateService();
            ChainPlayerState oldState = service.getOrCreatePlayerState(playerId);
            oldState.getDropBuffer().add(new ItemStack(new Item()));
            oldState.setChainKeyPressed(true);
            oldState.setRequestedChainMaxBlocks(17);
            service.removePlayerState(playerId, "test-disconnect");

            ChainPlayerState reconnected = service.getOrCreatePlayerState(playerId);
            Assert.assertNotSame("重连不能复活退休运行态", oldState, reconnected);
            Assert.assertSame("待交付资产沿用原buffer", oldState.getDropBuffer(), reconnected.getDropBuffer());
            Assert.assertFalse(reconnected.isChainKeyPressed());
            Assert.assertEquals(-1, reconnected.getRequestedChainMaxBlocks());
            Assert.assertFalse(reconnected.getDropBuffer().isEmpty());
            reconnected.getDropBuffer().drain();
            service.removeDeliveredDropOwner(oldState);
            Assert.assertSame("旧owner回收不能移除新运行态", reconnected, service.getPlayerState(playerId));
        } finally {
            MyMod.playerManager = previousManager;
        }
    }

    @Test
    public void removeWithoutReleaseContextKeepsDropsOwnedAndClaimable() {
        PlayerManager previousManager = MyMod.playerManager;
        MyMod.playerManager = null;
        try {
            UUID playerId = UUID.randomUUID();
            ChainStateService service = new ChainStateService();
            ChainPlayerState state = service.getOrCreatePlayerState(playerId);
            ChainPlayerDropBuffer savedBuffer = state.getDropBuffer();
            ItemStack drop = new ItemStack(new Item());
            savedBuffer.add(drop);
            state.setExecuting(true);

            service.removePlayerState(playerId, "test-missing-release-context");

            Assert.assertFalse("清理不能清空已收集资产", savedBuffer.isEmpty());
            ChainPlayerState owner = service.getPlayerState(playerId);
            Assert.assertNotNull("未交付的 buffer 必须仍可通过服务领取", owner);
            Assert.assertSame(savedBuffer, owner.getDropBuffer());
            Assert.assertFalse("只保留资产，不保留执行窗口", owner.isExecuting());
            List<ItemStack> claimed = owner.getDropBuffer().drain();
            Assert.assertEquals(1, claimed.size());
            Assert.assertSame(drop.getItem(), claimed.get(0).getItem());
            Assert.assertEquals(drop.stackSize, claimed.get(0).stackSize);

            service.removeDeliveredDropOwner(owner);
            Assert.assertNull("交付完成后可移除退休 owner", service.getPlayerState(playerId));
        } finally {
            MyMod.playerManager = previousManager;
        }
    }
}
