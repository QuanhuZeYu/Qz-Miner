package club.heiqi.qz_miner.toolswap.protocol;

import java.io.Serializable;

/** 将请求与其服务端响应绑定，用于安全缓存重复请求的完整结果。 */
public final class AutoToolSwapActionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AutoToolSwapIntent intent;
    private final AutoToolSwapRoundResult roundResult;

    public AutoToolSwapActionResult(AutoToolSwapIntent intent, AutoToolSwapRoundResult roundResult) {
        if (intent == null || roundResult == null) {
            throw new IllegalArgumentException("intent and roundResult must not be null");
        }
        this.intent = intent;
        this.roundResult = roundResult;
    }

    public AutoToolSwapIntent intent() {
        return intent;
    }

    public AutoToolSwapRoundResult roundResult() {
        return roundResult;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AutoToolSwapActionResult
                && intent.equals(((AutoToolSwapActionResult) other).intent)
                && roundResult.equals(((AutoToolSwapActionResult) other).roundResult));
    }

    @Override
    public int hashCode() {
        return 31 * intent.hashCode() + roundResult.hashCode();
    }
}
