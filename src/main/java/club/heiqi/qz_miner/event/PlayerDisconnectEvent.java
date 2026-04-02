package club.heiqi.qz_miner.event;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

/**
 * 玩家断开连接事件。
 *
 * 通过 Mixin 注入到 {@code NetHandlerPlayServer.onDisconnect} 中触发，
 * 比 Forge 的 {@code PlayerLoggedOutEvent} 更早、更准确。
 * 此时玩家数据完整，且可以获取断开原因。
 */
public class PlayerDisconnectEvent implements Event {

    /**
     * 断开连接的玩家。
     */
    public final EntityPlayerMP player;

    /**
     * 断开连接的原因。
     */
    public final IChatComponent reason;

    public PlayerDisconnectEvent(EntityPlayerMP player, IChatComponent reason) {
        this.player = player;
        this.reason = reason;
    }
}