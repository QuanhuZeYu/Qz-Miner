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
                // 按真实 endpoint identity 复核当前连接，再处理其松键恢复与清理。
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
                // 服务端主线程写入按键电平并发布观测，输入代际由状态机按当前阶段处理。
                if (keyId == ChainConstants.KEY_CHAIN && MyMod.chainEventBus != null) {
                    MyMod.chainEventBus.publish(new ChainKeyPressed(
                            playerId, serverRoundId, 0,
                            serverTick, ChainTickSource.nowNanos(),
                            pressed));
                    // 松键强制收口当前玩家任务，保留在线槽位代际；执行桥释放上下文并关闭窗口。
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
