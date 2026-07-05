package club.heiqi.qz_miner.chain.eventbus.event;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * {@link ChainPhaseChanged} 进态广播事件单测。
 */
public class ChainPhaseChangedTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000CD");
    private static final long TICK = 555L;
    private static final long NANOS = 987654321L;

    /** 构造器字段透传：父类 4 字段 + 子类 from/to。 */
    @Test
    public void constructorFieldsPreserved() {
        int gen = 9;
        ChainPhaseChanged event = new ChainPhaseChanged(
                PLAYER, gen, ChainPhase.ARMED, ChainPhase.PLANNING, TICK, NANOS);

        Assert.assertEquals(PLAYER, event.getPlayerUUID());
        Assert.assertEquals("gen 透传", gen, event.getGeneration());
        Assert.assertEquals(TICK, event.getServerTick());
        Assert.assertEquals(NANOS, event.getTimestampNanos());
        Assert.assertEquals(ChainPhase.ARMED, event.getFromPhase());
        Assert.assertEquals(ChainPhase.PLANNING, event.getToPhase());
    }

    /** fromPhase/toPhase ordinal 往返正确（序列化优化锚点）。 */
    @Test
    public void ordinalRoundTripCorrect() {
        for (ChainPhase from : ChainPhase.values()) {
            for (ChainPhase to : ChainPhase.values()) {
                ChainPhaseChanged event = new ChainPhaseChanged(
                        PLAYER, 1, from, to, TICK, NANOS);
                Assert.assertEquals("from ordinal 必须等于枚举 ordinal",
                        from.ordinal(), event.getFromPhaseOrdinal());
                Assert.assertEquals("to ordinal 必须等于枚举 ordinal",
                        to.ordinal(), event.getToPhaseOrdinal());
                Assert.assertEquals("from 枚举必须往返正确",
                        from, event.getFromPhase());
                Assert.assertEquals("to 枚举必须往返正确",
                        to, event.getToPhase());
            }
        }
    }

    /** 不可变：所有声明字段必须 final（守阶段1 ChainEventImmutabilityTest 契约）。 */
    @Test
    public void allFieldsAreFinal() {
        for (Field f : ChainPhaseChanged.class.getDeclaredFields()) {
            Assert.assertTrue(
                    "field " + f.getName() + " must be final (守 ChainEventImmutability)",
                    Modifier.isFinal(f.getModifiers()));
        }
    }

    /** 边界：gen=0 + IDLE→IDLE 同态转移（状态机可能 publish 的边界情形）。 */
    @Test
    public void zeroGenAndSamePhase() {
        ChainPhaseChanged event = new ChainPhaseChanged(
                PLAYER, 0, ChainPhase.IDLE, ChainPhase.IDLE, 0L, 0L);
        Assert.assertEquals(0, event.getGeneration());
        Assert.assertEquals(ChainPhase.IDLE, event.getFromPhase());
        Assert.assertEquals(ChainPhase.IDLE, event.getToPhase());
    }
}
