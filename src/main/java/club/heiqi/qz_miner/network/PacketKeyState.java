package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.ChainConstants;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapKeyStateBridge;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CloseCause;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端按键状态同步包。
 *
 * 客户端长按/松开按键时发送此包到服务端，
 * 服务端据此更新对应玩家的状态。
 */
public class PacketKeyState implements IMessage {

    /**
     * 按键是否处于按下状态。
     */
    public boolean pressed;

    /**
     * 按键标识符。
     */
    public int keyId;

    public PacketKeyState() {
    }

    public PacketKeyState(int keyId, boolean pressed) {
        this.keyId = keyId;
        this.pressed = pressed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.keyId = buf.readInt();
        this.pressed = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.keyId);
        buf.writeBoolean(this.pressed);
    }

    /**
     * 服务端处理器。
     */
    public static class KeyStatePacketHandler implements IMessageHandler<PacketKeyState, IMessage> {

        @Override
        public IMessage onMessage(PacketKeyState message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            final UUID playerId = player.getUniqueID();
            final WeakReference<EntityPlayerMP> endpoint = new WeakReference<EntityPlayerMP>(player);
            final int keyId = message.keyId;
            final boolean pressed = message.pressed;
            ServerMainThreadDispatcher.run(() -> {
                // 同 UUID 重连后，旧 endpoint 的迟到松键不得 restore 或 forced-cleanup 新会话。
                EntityPlayerMP captured = endpoint.get();
                EntityPlayer current = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
                if (captured == null || current != captured) {
                    return;
                }

                long serverTick = Math.max(0L, ChainTickSource.currentServerTick());
                // 松键是 local physical owner 的可靠屏障：必须早于 round projection、连锁状态和
                // LifecycleCleanup 消费者，确保 restore -> publication attempt -> cleanup。
                if (keyId == ChainConstants.KEY_CHAIN && !pressed
                        && MyMod.autoToolSwapServerBatchService != null) {
                    MyMod.autoToolSwapServerBatchService.finalizePlayer(playerId, captured, null,
                            CloseCause.KEY_RELEASE, serverTick);
                }
                long serverRoundId = 0L;
                if (keyId == ChainConstants.KEY_CHAIN) {
                    serverRoundId = AutoToolSwapKeyStateBridge.onKeyState(captured, pressed);
                }
                if (keyId == ChainConstants.KEY_CHAIN && MyMod.chainStateService != null) {
                    MyMod.chainStateService.setPlayerChainKeyPressed(playerId, pressed);
                }
                // 守跨线程只经事件总线：publish 在 ServerMainThreadDispatcher.run lambda 内（line 60），已收口主线程
                // 阶段3影子并行：保留旧 setPlayerChainKeyPressed，新链路仅推进状态机观测
                // 输入事件 generation 传 0 豁免代际判定（由转移规则本身约束消费态）
                if (keyId == ChainConstants.KEY_CHAIN && MyMod.chainEventBus != null) {
                    MyMod.chainEventBus.publish(new ChainKeyPressed(
                            playerId, serverRoundId, 0,
                            serverTick, ChainTickSource.nowNanos(),
                            pressed));
                    // E2 松键即停修复：松键（pressed=false）额外 publish LifecycleCleanup(reason="user-abort",
                    // forced=true, removeSlot=false)。复用 forced=true 豁免 genCheck，无论当前处于
                    // PLANNING/RUNNING/FINISHING/ARMED/IDLE 哪个态都能由状态机 T9/T8/IDLE early-return 收口
                    // （活跃态→IDLE 走 T9，IDLE 幂等丢弃）。removeSlot=false：玩家在线保 gen 单调，
                    // 后续连锁可正常武装。执行桥 onLifecycleCleanup 关掉落窗口 + 清 registry，
                    // 中断活跃执行链（边搜边破坏的活跃连锁松键即停）。
                    if (!pressed) {
                        MyMod.chainEventBus.publish(new LifecycleCleanup(
                                playerId, serverRoundId, 0,
                                serverTick, ChainTickSource.nowNanos(),
                                "user-abort", true, false));
                    }
                }
                MyMod.LOG.debug("[Network] Player {} key state: keyId={}, pressed={}",
                    captured.getCommandSenderName(), keyId, pressed);
            });
            return null;
        }
    }
}
