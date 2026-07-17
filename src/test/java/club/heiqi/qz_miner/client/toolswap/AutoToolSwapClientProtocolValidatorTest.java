package club.heiqi.qz_miner.client.toolswap;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** validator 只做单包 raw/enum/range 校验且不持久化任何关联状态。 */
public class AutoToolSwapClientProtocolValidatorTest {

    private final AutoToolSwapClientProtocolValidator validator =
            new AutoToolSwapClientProtocolValidator();

    @Test
    public void validatorHasNoFieldsAndAcceptsStructurallyValidPackets() {
        Assert.assertEquals(0, AutoToolSwapClientProtocolValidator.class.getDeclaredFields().length);
        Assert.assertNotNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 7L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        Assert.assertEquals(AutoToolSwapAction.SWAP, validator.validateActionResult(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 7L, 1L, AutoToolSwapAction.SWAP.wireCode(),
                AutoToolSwapResultCode.APPLIED.wireCode(), AutoToolSwapRoundState.SWAPPED.wireCode(),
                0, 5, 2L, 1L, true).action());
        Assert.assertEquals(AutoToolSwapAction.ABANDON, validator.validateActionResult(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 7L, 2L, AutoToolSwapAction.ABANDON.wireCode(),
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.FINISHED.wireCode(),
                0, 5, 3L, 1L, true).action());
        Assert.assertEquals(ChainPhase.RUNNING, validator.validatePhase(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 7L, 1L, ChainPhase.RUNNING.ordinal(),
                3, 2L, true).phase());
        Assert.assertNotNull(validator.validateTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION,
                7L, 2L, 3, 1, 64, 2, 42, 7, 10L, 18L, true));
    }

    @Test
    public void malformedRawEnumsAndRangesAreRejectedWithoutHistory() {
        Assert.assertNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 7L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, false));
        Assert.assertNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION + 1, 7L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        Assert.assertNull(validator.validateRoundResult(1, 7L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        Assert.assertNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, -1L,
                Integer.MAX_VALUE, Integer.MAX_VALUE, 0L, -1L, true));
        Assert.assertNull(validator.validateActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 7L,
                0L, Integer.MAX_VALUE, AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), -1, 36, 2L, 1L, true));
        Assert.assertNull(validator.validateActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 7L,
                1L, AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.PENDING_KEY.wireCode(), 0, 5, 2L, 1L, true));
        Assert.assertNull(validator.validatePhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 0L, 0L,
                ChainPhase.values().length, -1, -1L, true));
        Assert.assertNull(validator.validateTakeoverRequest(2, 7L, 2L, 3,
                1, 64, 2, 42, 7, 10L, 18L, true));
    }

    @Test
    public void validatorDoesNotJudgeNonceRoundOrSequenceHistory() {
        Assert.assertNotNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                4L, 3L, true));
        Assert.assertNotNull(validator.validateRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 8L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                2L, 1L, true));
        Assert.assertNotNull(validator.validatePhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L,
                ChainPhase.RUNNING.ordinal(), 2, 3L, true));
        Assert.assertNotNull(validator.validatePhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 8L, 1L,
                ChainPhase.ARMED.ordinal(), 0, 1L, true));
    }
}
