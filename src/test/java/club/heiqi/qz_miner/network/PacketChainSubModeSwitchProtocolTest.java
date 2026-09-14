package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 子模式 ordinal 单 int wire framing 与边界解码回归。
 *
 * <p>framing 由真实 round-trip 守（写 4 字节、读回后必须恰好读完 4 字节），越界回退语义由
 * {@link PacketChainSubModeSwitch#resolveSubMode(int)} 按值守——两者都不再读源码文本比对。</p>
 */
public class PacketChainSubModeSwitchProtocolTest {

    private static final String PACKET_DIR = "src/main/java/club/heiqi/qz_miner/network/";

    @Test
    public void allLegacyAndAppendedOrdinalsRoundTripAsExactlyFourBytes() {
        for (ChainSubMode mode : ChainSubMode.values()) {
            PacketChainSubModeSwitch source = new PacketChainSubModeSwitch(mode);
            ByteBuf buffer = Unpooled.buffer(4);
            source.toBytes(buffer);

            Assert.assertEquals(mode.name(), 4, buffer.readableBytes());
            PacketChainSubModeSwitch decoded = new PacketChainSubModeSwitch();
            decoded.fromBytes(buffer);
            Assert.assertEquals(mode.name() + " 的 fromBytes 必须恰好消费 4 字节 framing",
                    0, buffer.readableBytes());
            Assert.assertEquals(mode.name(), mode.ordinal(), decoded.subModeOrdinal);
            Assert.assertSame(mode.name(), mode, ChainSubMode.values()[decoded.subModeOrdinal]);
        }
    }

    @Test
    public void packetKeepsSingleIntAndBoundsCheckedUnknownOrdinalFallback() {
        ChainSubMode[] values = ChainSubMode.values();
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            Assert.assertSame("合法 ordinal 必须命中对应枚举: " + ordinal,
                    values[ordinal], PacketChainSubModeSwitch.resolveSubMode(ordinal));
        }
        Assert.assertNull("负 ordinal 必须回退 null", PacketChainSubModeSwitch.resolveSubMode(-1));
        Assert.assertNull("等于枚举长度的越界 ordinal 必须回退 null",
                PacketChainSubModeSwitch.resolveSubMode(values.length));
        Assert.assertNull("Integer.MIN_VALUE 必须回退 null",
                PacketChainSubModeSwitch.resolveSubMode(Integer.MIN_VALUE));
        Assert.assertNull("Integer.MAX_VALUE 必须回退 null",
                PacketChainSubModeSwitch.resolveSubMode(Integer.MAX_VALUE));
    }

    @Test
    public void modeHandlersRejectStaleConnectionBeforeMutatingPlayerState() throws Exception {
        assertEndpointGatePrecedesMutation("PacketChainModeSwitch.java", "setPlayerSelectedMode(playerId, mode)");
        assertEndpointGatePrecedesMutation(
                "PacketChainSubModeSwitch.java", "setPlayerSelectedSubMode(playerId, subMode)");
    }

    /**
     * 「端点捕获 → 身份门 → 玩家状态写入」的先后契约，窗口限定在 Handler.onMessage 方法体内：
     * 注释或其它方法里出现同样字串不再误判，而把身份门挪到写入之后会红。
     *
     * <p>行为化不可达的实测依据：{@code onMessage} 第一句即 {@code ctx.getServerHandler().playerEntity}，
     * 造得出 {@code EntityPlayerMP} 需要真实 MinecraftServer/世界，且随后走 {@code MyMod} 的静态服务装配；
     * 仓库无 mock 框架（依赖里没有 mockito），因此只保留结构契约。</p>
     */
    private static void assertEndpointGatePrecedesMutation(String fileName, String mutation) throws Exception {
        String handlerBody = JavaSourceSlices.methodBodyWithoutSignature(
                JavaSourceSlices.maskedMainSource(PACKET_DIR + fileName), "onMessage");
        JavaSourceSlices.assertBefore(handlerBody, "new WeakReference<EntityPlayerMP>(player)", "current != captured",
                fileName + "：必须先用端点弱引用捕获连接，再比对身份拒绝过期连接");
        JavaSourceSlices.assertBefore(handlerBody, "current != captured", mutation,
                fileName + "：身份门必须先于玩家状态写入");
    }
}
