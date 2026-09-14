package club.heiqi.qz_miner.client.configGUI;

/**
 * 颜色字段的 HEX ↔ int（{@code 0xRRGGBB}）互转（纯静态函数，无状态、无控件依赖）。
 *
 * <p>配置里颜色是 int {@code 0xRRGGBB}（schema 以 Double 承载，见
 * {@code ConfigSemanticValidator} 的「必须有限 / 必须整数 / 区间 [0,0xFFFFFF]」校验），
 * 配置页显示为 {@code #RRGGBB}。本类是该显示形态唯一的编解码点：渲染器只调这里，
 * 不另写字面量规则（显示格式与来源保持单一真源）。</p>
 *
 * <h3>接受的输入形态</h3>
 * <ul>
 *   <li>{@code #RRGGBB}——显示形态本身，大小写皆可；</li>
 *   <li>{@code 0xRRGGBB} / {@code 0XRRGGBB}——schema helper 里写的形态（照抄 helper 也能过）；</li>
 *   <li>{@code RRGGBB}——不带前缀但含十六进制字母的六位形态（如 {@code 40E6FF}）；</li>
 *   <li>纯十进制 {@code 4253439}——改造前数字框的唯一形态，必须继续可用。</li>
 * </ul>
 *
 * <h3>歧义裁决（六位纯数字，如 {@code 123456}）</h3>
 * <p>既可读作十六进制也可读作十进制。裁决取<b>十进制</b>：改造前该形态就是十进制，
 * 取十六进制会让存量值与用户习惯静默换义；需要十六进制时加 {@code #}（或 {@code 0x}），
 * 这也正是字段的显示形态。</p>
 *
 * <h3>非法输入</h3>
 * <p>位数不为 6、含非十六进制字符、带符号/小数点/指数、越界（&lt;0 或 &gt;0xFFFFFF）一律返回
 * {@code null}；<b>不夹取、不四舍五入、不回落默认值</b>——非法原文由调用方走 DraftBuffer 校验报错路径。</p>
 */
public final class HexColorCodec {

    /** 颜色值域上界（{@code 0xRRGGBB}，与 schema {@code range(0, 0xFFFFFF)} 同源）。 */
    public static final int MAX_RGB = 0xFFFFFF;

    /** 十六进制位数（{@code RRGGBB}）。 */
    private static final int HEX_DIGITS = 6;

    /** 十进制位数上界（{@code 16777215} 为 8 位，多于此必越界）。 */
    private static final int DECIMAL_DIGITS_MAX = 8;

    private HexColorCodec() {
    }

    /**
     * 编码：{@code 0xRRGGBB} → {@code #RRGGBB}（大写十六进制，取低 24 位）。
     *
     * @param rgb 颜色值
     * @return 显示文本，恒为 7 字符
     */
    public static String format(int rgb) {
        char[] out = new char[HEX_DIGITS + 1];
        out[0] = '#';
        for (int i = 0; i < HEX_DIGITS; i++) {
            int nibble = (rgb >>> ((HEX_DIGITS - 1 - i) * 4)) & 0xF;
            out[i + 1] = (char) (nibble < 10 ? '0' + nibble : 'A' + (nibble - 10));
        }
        return new String(out);
    }

    /**
     * 解码：接受 {@link HexColorCodec 类头}列出的四种形态。
     *
     * @param text 用户输入文本，可为 null
     * @return 颜色值；非法时 {@code null}（不抛异常、不返回近似值）
     */
    public static Integer parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.charAt(0) == '#') {
            return parseHex(trimmed.substring(1));
        }
        if (trimmed.length() > 2 && (trimmed.startsWith("0x") || trimmed.startsWith("0X"))) {
            return parseHex(trimmed.substring(2));
        }
        // 无前缀：含十六进制字母的六位串读作十六进制，其余按十进制（歧义裁决见类头）
        if (trimmed.length() == HEX_DIGITS && containsHexLetter(trimmed) && isHexDigits(trimmed)) {
            return parseHex(trimmed);
        }
        return parseDecimal(trimmed);
    }

    /** 六位十六进制 → 值；位数不符或含非法字符返回 null。 */
    private static Integer parseHex(String digits) {
        if (digits.length() != HEX_DIGITS) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < HEX_DIGITS; i++) {
            int digit = hexDigit(digits.charAt(i));
            if (digit < 0) {
                return null;
            }
            value = (value << 4) | digit;
        }
        return Integer.valueOf(value);
    }

    /** 纯十进制 → 值；只允许数字字符、值域 [0,0xFFFFFF]（前导零不影响判读）。 */
    private static Integer parseDecimal(String text) {
        int firstSignificant = 0;
        while (firstSignificant < text.length() && text.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        String digits = text.substring(firstSignificant);
        if (digits.isEmpty()) {
            return Integer.valueOf(0);
        }
        if (digits.length() > DECIMAL_DIGITS_MAX) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        return value > MAX_RGB ? null : Integer.valueOf(value);
    }

    /** @return 单个字符的十六进制值，非法返回 -1 */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** @return 是否全部为十六进制字符（空串返回 false） */
    private static boolean isHexDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (hexDigit(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** @return 是否含十六进制字母（a-f/A-F）——用于把「六位纯数字」排除出十六进制分支 */
    private static boolean containsHexLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                return true;
            }
        }
        return false;
    }
}
