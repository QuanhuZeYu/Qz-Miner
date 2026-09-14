package club.heiqi.qz_miner.client.configGUI;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/**
 * 预览颜色字段的 HEX ↔ int 互转与注册接线（纯 JVM，不建 scene）。
 *
 * <p>断言口径：互转是纯函数，直连断言取值与拒绝；注册以 schema 为锚断言「这四个键被换成
 * 非默认渲染器」——路径写错会让功能静默不生效，这是唯一能在离线暴露它的地方。</p>
 */
public class PreviewColorFieldRendererTest {

    /** 任务口径的四个颜色键（与 {@link PreviewColorFieldRenderer#paths()} 必须一致）。 */
    private static final String[] COLOR_PATHS = {
            "client.clientPreviewColorPrimary",
            "client.clientPreviewColorSecondary",
            "client.clientPreviewColorRemote",
            "client.clientPreviewColorTruncated"};

    // ==================================================================
    // HEX ↔ int
    // ==================================================================

    /** 四种文档化形态（含大小写与首尾空白）都解出同一个颜色。 */
    @Test
    public void parseAcceptsEveryDocumentedForm() {
        int expected = 0x40E6FF;
        assertParses(expected, "#40E6FF");
        assertParses(expected, "#40e6ff");
        assertParses(expected, "40E6FF");
        assertParses(expected, "40e6ff");
        assertParses(expected, "0x40E6FF");
        assertParses(expected, "0X40e6ff");
        assertParses(expected, "  #40E6FF  ");
        // 改造前的显示形态（十进制）继续可用，且与 HEX 解出同一个值
        assertParses(expected, "4253439");
        Assert.assertEquals("4253439 与 #40E6FF 必须同值",
                HexColorCodec.parse("#40E6FF"), HexColorCodec.parse("4253439"));
    }

    /** 六位纯数字的歧义裁决：读十进制（存量语义不变），要十六进制必须带 # 或 0x。 */
    @Test
    public void parseTreatsSixDigitDigitsOnlyTextAsDecimal() {
        assertParses(123456, "123456");
        Assert.assertNotEquals("六位纯数字不得读成十六进制",
                Integer.valueOf(0x123456), HexColorCodec.parse("123456"));
        assertParses(0x123456, "#123456");
        assertParses(0x123456, "0x123456");
        assertParses(255, "0000255");
        assertParses(0, "0");
        assertParses(0, "00000000");
        assertParses(HexColorCodec.MAX_RGB, "16777215");
        Assert.assertNull("十进制越界不夹取", HexColorCodec.parse("16777216"));
    }

    /** 非法输入一律 null：不抛异常、不夹取、不四舍五入、不回落默认值。 */
    @Test
    public void parseRejectsMalformedAndOutOfRangeText() {
        String[] rejected = {
                null, "", "   ", "#", "#12345", "#1234567", "#40E6FG", "#-12345",
                "-1", "+255", "1e5", "255.0", "40E6F", "40E6FFF", "GGGGGG",
                "0x12345", "0xGGGGGG", "16777216", "99999999", "4294967296", "# 123456"};
        for (String text : rejected) {
            Assert.assertNull("必须拒绝: [" + text + "]", HexColorCodec.parse(text));
        }
    }

    /** 编码恒为 7 字符大写 #RRGGBB，取低 24 位；与解码互为逆运算（含值域两端）。 */
    @Test
    public void formatIsUppercaseHashHexAndRoundTripsAtValueRangeBoundaries() {
        Assert.assertEquals("#000000", HexColorCodec.format(0x000000));
        Assert.assertEquals("#FFFFFF", HexColorCodec.format(0xFFFFFF));
        Assert.assertEquals("#40E6FF", HexColorCodec.format(0x40E6FF));
        Assert.assertEquals("#0000AB", HexColorCodec.format(0x0000AB));
        Assert.assertEquals("#40E6FF", HexColorCodec.format(0xFF40E6FF));

        int[] values = {0x000000, 0x0000FF, 0x00FF00, 0xFF0000, 0x40E6FF, 0xFFFFFF};
        for (int value : values) {
            assertParses(value, HexColorCodec.format(value));
        }
    }

    // ==================================================================
    // 显示文本派生（单真源：只有草稿值 + 编辑期原文两种来源）
    // ==================================================================

    /** 合法颜色 → #RRGGBB；其余原样透出，绝不显示成一个并不存在的颜色。 */
    @Test
    public void displayTextDerivesFromValueAndNeverFabricatesAColor() {
        Assert.assertEquals("", PreviewColorFieldRenderer.displayText(null));
        Assert.assertEquals("#40E6FF", PreviewColorFieldRenderer.displayText(Double.valueOf(0x40E6FF)));
        Assert.assertEquals("#000000", PreviewColorFieldRenderer.displayText(Double.valueOf(0)));
        Assert.assertEquals("#FFFFFF", PreviewColorFieldRenderer.displayText(Double.valueOf(0xFFFFFF)));
        // 越界/非整数数字：如实十进制原文，不环绕成假颜色
        Assert.assertEquals("16777216", PreviewColorFieldRenderer.displayText(Double.valueOf(16777216)));
        Assert.assertEquals("0.5", PreviewColorFieldRenderer.displayText(Double.valueOf(0.5)));
        // 非法原文（用户敲进草稿的字符串）原样显示
        Assert.assertEquals("#40E6F", PreviewColorFieldRenderer.displayText("#40E6F"));
    }

    // ==================================================================
    // 注册接线
    // ==================================================================

    /** 四个键都在 schema 里且是区间 [0,0xFFFFFF] 的 NUMBER——路径写错会让 HEX 控件静默不生效。 */
    @Test
    public void pathsCoverExactlyTheFourSchemaColorKeys() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Set<String> expected = new LinkedHashSet<String>(Arrays.asList(COLOR_PATHS));
        Assert.assertEquals("覆盖路径集合必须恰为四个颜色键",
                expected, new LinkedHashSet<String>(Arrays.asList(PreviewColorFieldRenderer.paths())));
        for (String path : COLOR_PATHS) {
            Assert.assertNotNull("schema 缺少字段: " + path, schema.field(path));
            Assert.assertEquals(path + " 必须是 NUMBER", FieldType.NUMBER, schema.field(path).type());
            Assert.assertEquals(path + " 区间下界", 0.0D, schema.field(path).constraints().min(), 0.0D);
            Assert.assertEquals(path + " 区间上界", (double) HexColorCodec.MAX_RGB,
                    schema.field(path).constraints().max(), 0.0D);
        }
    }

    /** install 只覆盖这四个键：颜色键解析到非默认渲染器，其它 NUMBER 键仍是类型默认渲染器。 */
    @Test
    public void installOverridesExactlyTheFourColorPaths() {
        FieldRendererRegistry delegates = FieldRendererRegistry.defaultRegistry();
        PreviewColorFieldRenderer.install(delegates);
        ConfigSchema schema = QzMinerConfigSchema.create();

        FieldRenderer numberDefault = delegates.resolve(schema.field("client.clientPreviewBarThickness"));
        Assert.assertNotNull(numberDefault);
        for (String path : COLOR_PATHS) {
            FieldRenderer resolved = delegates.resolve(schema.field(path));
            Assert.assertNotNull("颜色键未被覆盖: " + path, resolved);
            Assert.assertNotSame("颜色键必须换成专用渲染器: " + path, numberDefault, resolved);
        }
        Assert.assertSame("非颜色 NUMBER 键不得被覆盖", numberDefault,
                delegates.resolve(schema.field("client.clientPreviewMaxRadius")));
    }

    private static void assertParses(int expected, String text) {
        Assert.assertEquals("解析 " + text, Integer.valueOf(expected), HexColorCodec.parse(text));
    }
}
