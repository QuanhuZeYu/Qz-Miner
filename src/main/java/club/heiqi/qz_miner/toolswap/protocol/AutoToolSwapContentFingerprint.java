package club.heiqi.qz_miner.toolswap.protocol;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 严格内容指纹：SHA-256(UTF-8(roleKey) + NUL + UTF-8(dynamicFingerprint))。
 * 完整 256-bit 摘要以四个 long 保留，绝不截短为 hashCode。
 */
public final class AutoToolSwapContentFingerprint implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public static final AutoToolSwapContentFingerprint CANONICAL_EMPTY = fromContent(
            AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY, "");

    private final long firstLong;
    private final long secondLong;
    private final long thirdLong;
    private final long fourthLong;

    private AutoToolSwapContentFingerprint(long firstLong, long secondLong, long thirdLong, long fourthLong) {
        this.firstLong = firstLong;
        this.secondLong = secondLong;
        this.thirdLong = thirdLong;
        this.fourthLong = fourthLong;
    }

    /** 根据角色与动态内容计算完整 SHA-256 指纹。 */
    public static AutoToolSwapContentFingerprint fromContent(String roleKey, String dynamicFingerprint) {
        if (roleKey == null || roleKey.length() == 0 || dynamicFingerprint == null) {
            throw new IllegalArgumentException("roleKey must be non-empty and dynamicFingerprint must not be null");
        }
        MessageDigest digest = sha256();
        digest.update(roleKey.getBytes(UTF_8));
        digest.update((byte) 0);
        byte[] bytes = digest.digest(dynamicFingerprint.getBytes(UTF_8));
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return fromWire(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
    }

    /** 从完整的四个 wire long 重建指纹。 */
    public static AutoToolSwapContentFingerprint fromWire(long firstLong, long secondLong,
            long thirdLong, long fourthLong) {
        return new AutoToolSwapContentFingerprint(firstLong, secondLong, thirdLong, fourthLong);
    }

    /** @return canonical empty 内容的完整指纹。 */
    public static AutoToolSwapContentFingerprint canonicalEmpty() {
        return CANONICAL_EMPTY;
    }

    public long firstLong() {
        return firstLong;
    }

    public long secondLong() {
        return secondLong;
    }

    public long thirdLong() {
        return thirdLong;
    }

    public long fourthLong() {
        return fourthLong;
    }

    /**
     * 以四段 XOR 聚合完成内容比较；除 null 防护外不按任一段提前返回。
     *
     * @return 两个完整 256-bit 指纹是否相同
     */
    public boolean sameContent(AutoToolSwapContentFingerprint other) {
        return other != null && ((firstLong ^ other.firstLong) | (secondLong ^ other.secondLong)
                | (thirdLong ^ other.thirdLong) | (fourthLong ^ other.fourthLong)) == 0L;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof AutoToolSwapContentFingerprint
                && sameContent((AutoToolSwapContentFingerprint) other));
    }

    @Override
    public int hashCode() {
        long folded = firstLong ^ secondLong ^ thirdLong ^ fourthLong;
        return (int) (folded ^ (folded >>> 32));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", error);
        }
    }
}
