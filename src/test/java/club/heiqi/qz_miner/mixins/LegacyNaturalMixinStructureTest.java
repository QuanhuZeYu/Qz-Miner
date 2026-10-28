package club.heiqi.qz_miner.mixins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** 验证 legacy 自然矿 mixin 的编译目标、注入描述符与 common 类型依赖边界。 */
public class LegacyNaturalMixinStructureTest {

    private static final String DEFINITION = "Lcom/llamalad7/mixinextras/expression/Definition;";
    private static final String EXPRESSION = "Lcom/llamalad7/mixinextras/expression/Expression;";
    private static final String NATURAL_HANDLER = "qzMiner$treatPlacedOreAsNatural";

    /** GT mixin 仅以注解字符串描述精确字段，不产生第三方类型 Class 常量。 */
    @Test
    public void gtMixinContainsExactFieldTargetWithoutLinkedClassConstant() throws Exception {
        assertFieldTarget(MixinTileEntityOresLegacy.class, "gregtech/",
                "Lgregtech/common/blocks/TileEntityOres;mNatural:Z");
    }

    /** BW mixin 仅以注解字符串描述精确字段，不产生第三方类型 Class 常量。 */
    @Test
    public void bwMixinContainsExactFieldTargetWithoutLinkedClassConstant() throws Exception {
        assertFieldTarget(MixinBWTileEntityMetaGeneratedOreLegacy.class, "bartworks/",
                "Lbartworks/system/material/BWTileEntityMetaGeneratedOre;mNatural:Z");
    }

    private static void assertFieldTarget(Class<?> mixin, String thirdPartyPrefix, String fieldTarget)
            throws IOException {
        File produced = CompiledClasses.forInternalName(mixin.getName().replace('.', '/'));
        assertFalse("不得链接第三方类型（常量池类型引用面不得出现 " + thirdPartyPrefix + "）: "
                + CompiledClasses.classRefs(produced),
                CompiledClasses.refs(produced).hasClassRefUnder(thirdPartyPrefix));
        assertTrue("字段目标必须以精确描述符出现在编译产物：" + fieldTarget,
                CompiledClasses.references(mixin, fieldTarget));
        assertTrue("表达式必须精确指向 this.natural", CompiledClasses.references(mixin, "this.natural"));
        List<String> annotations = annotationsOf(produced, NATURAL_HANDLER);
        assertTrue("@Definition 必须挂在字段 handler " + NATURAL_HANDLER + " 上：" + annotations,
                annotations.contains(DEFINITION));
        assertTrue("@Expression 必须挂在同一 handler 上：" + annotations,
                annotations.contains(EXPRESSION));
    }

    private static List<String> annotationsOf(File produced, String methodName) throws IOException {
        for (CompiledClasses.MethodInfo method : CompiledClasses.methods(produced)) {
            if (methodName.equals(method.name)) {
                return method.annotations;
            }
        }
        throw new AssertionError("缺少字段注入 handler: " + methodName);
    }
}
