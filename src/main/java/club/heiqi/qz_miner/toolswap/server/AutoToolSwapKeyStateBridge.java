package club.heiqi.qz_miner.toolswap.server;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.network.PacketAutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import net.minecraft.entity.player.EntityPlayerMP;

/** 在服务端主线程将连锁按键状态映射为工具换位 round 激活与释放。 */
public final class AutoToolSwapKeyStateBridge {

    private AutoToolSwapKeyStateBridge() {
    }

    /**
     * 处理生产环境按键状态。
     *
     * @param player 服务端玩家 endpoint
     * @param pressed 是否按下
     * @return 本次按键关联的服务端 round id，不关联时返回 0
     */
    public static long onKeyState(EntityPlayerMP player, boolean pressed) {
        if (player == null || MyMod.autoToolSwapRoundService == null) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        return onKeyState(player.getUniqueID(), player, pressed, MyMod.autoToolSwapRoundService,
                Math.max(0L, ChainTickSource.currentServerTick()), productionSender());
    }

    /**
     * 处理可注入 service 与 sender 的纯逻辑按键状态。
     *
     * @param playerId 玩家 UUID
     * @param endpoint 当前连接 endpoint identity
     * @param pressed 是否按下
     * @param service round service
     * @param serverTick 服务端 tick
     * @param sender 激活结果发送边界
     * @return 本次按键关联的服务端 round id，不关联时返回 0
     */
    public static long onKeyState(UUID playerId, Object endpoint, boolean pressed, AutoToolSwapRoundService service,
            long serverTick, RoundResultSender sender) {
        if (playerId == null || endpoint == null || service == null || sender == null || serverTick < 0L) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        if (!pressed) {
            return service.onKeyReleased(playerId, endpoint);
        }

        AutoToolSwapRoundSnapshot snapshot = service.snapshot(playerId, endpoint);
        if (snapshot == null || isTerminal(snapshot.roundState())) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        AutoToolSwapRoundResult result = service.activatePendingRound(playerId, endpoint, serverTick);
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED
                || result.serverRoundId() == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || isTerminal(result.roundState())) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        sender.send(playerId, endpoint, snapshot.clientNonce(), result);
        return result.serverRoundId();
    }

    private static boolean isTerminal(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.FINISHED || state == AutoToolSwapRoundState.ORPHANED;
    }

    private static RoundResultSender productionSender() {
        return new RoundResultSender() {
            @Override
            public void send(UUID playerId, Object endpoint, long clientNonce, AutoToolSwapRoundResult result) {
                if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                    MyMod.networkMain.network.sendTo(new PacketAutoToolSwapRoundResult(
                            AutoToolSwapProtocol.PROTOCOL_VERSION, clientNonce, result), (EntityPlayerMP) endpoint);
                }
            }
        };
    }

    /** 激活 round result 的可注入下发边界。 */
    public interface RoundResultSender {
        void send(UUID playerId, Object endpoint, long clientNonce, AutoToolSwapRoundResult result);
    }
}
