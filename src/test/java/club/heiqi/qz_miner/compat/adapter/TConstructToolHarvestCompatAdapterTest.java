package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import tconstruct.library.tools.HarvestTool;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** TiC 工具类形识别、旧式虚调用与隔离边界。 */
public class TConstructToolHarvestCompatAdapterTest {

    private static final String ADAPTER_TYPE =
            "club/heiqi/qz_miner/compat/adapter/TConstructToolHarvestCompatAdapter";
    private static final String HARVEST_TOOL_FQCN = "tconstruct.library.tools.HarvestTool";

    private final TConstructToolHarvestCompatAdapter adapter = new TConstructToolHarvestCompatAdapter();
    private final Block target = new TestBlock();

    /** 精确父类层级命中后分别保留 ALLOW 与 DENY。 */
    @Test
    public void exactRuntimeHierarchyUsesLegacyItemVirtualCall() {
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.ALLOW,
                evaluate(new SyntheticTool(true)));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.DENY,
                evaluate(new SyntheticTool(false)));
    }

    /** 普通 Item 与相似简单类名都不适用。 */
    @Test
    public void ordinaryAndSimilarNamesAreNotApplicable() {
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.NOT_APPLICABLE,
                evaluate(new OrdinaryLegacyItem()));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.NOT_APPLICABLE,
                evaluate(new HarvestToolLookalike()));
    }

    /** 无效类形和调用异常必须 UNRESOLVED。 */
    @Test
    public void invalidShapeAndInvocationFailureAreUnresolved() {
        Assert.assertEquals(ClassNameCompatSupport.HierarchyMatch.UNRESOLVED,
                ClassNameCompatSupport.matchesHierarchyName(null, "example.Type"));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                adapter.evaluate(null, new ItemStack(new Item()), target));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                evaluate(new ThrowingTool()));
    }

    /**
     * 生产适配器只保留唯一 TiC FQCN，且不加载或反射解析可选类型。
     *
     * <p>断言对象是编译产物：唯一可选名取自真实的私有常量与常量池字符串集合，
     * 「不链接 / 不加载 / 不做成员反射」取常量池的类型引用与成员引用集合。</p>
     */
    @Test
    public void sourceHasSingleOptionalNameAndNoTypeLoadingOrMemberReflection() throws Exception {
        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(ADAPTER_TYPE));

        Field constant = TConstructToolHarvestCompatAdapter.class.getDeclaredField("HARVEST_TOOL_TYPE");
        constant.setAccessible(true);
        Assert.assertEquals("唯一可选 FQCN 常量必须精确指向 TiC HarvestTool",
                HARVEST_TOOL_FQCN, constant.get(null));
        Set<String> optionalNames = new LinkedHashSet<String>();
        for (String value : refs.strings) {
            if (value.startsWith("tconstruct.")) {
                optionalNames.add(value);
            }
        }
        Assert.assertEquals("全类只允许唯一一个 TiC 可选类型名（多一个常量或多一处内联字面量都要吵）: "
                + optionalNames, Collections.singleton(HARVEST_TOOL_FQCN), optionalNames);

        Assert.assertFalse("不得静态链接 TiC（常量池不得出现 tconstruct/ 类型）: " + refs.classRefs,
                refs.hasClassRefUnder("tconstruct/"));
        Assert.assertFalse("不得加载可选类型：Class.forName",
                refs.methodRefs.contains("java/lang/Class#forName"));
        Assert.assertFalse("层级名匹配不得走类型加载入口 resolveClass",
                CompiledClasses.references(TConstructToolHarvestCompatAdapter.class, "resolveClass"));
        Assert.assertFalse("不得做成员反射（只做类名层级匹配）",
                CompiledClasses.references(TConstructToolHarvestCompatAdapter.class, "getMethod"));
    }

    private ToolHarvestCompatAdapter.Result evaluate(Item item) {
        return adapter.evaluate(item, new ItemStack(item), target);
    }

    /** 测试目标。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }

    /** 精确 TiC 合成层级。 */
    private static class SyntheticTool extends HarvestTool {
        private final boolean result;

        private SyntheticTool(boolean result) {
            this.result = result;
        }

        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            return result;
        }
    }

    /** 精确层级上的调用异常。 */
    private static final class ThrowingTool extends SyntheticTool {
        private ThrowingTool() {
            super(false);
        }

        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            throw new IllegalStateException("synthetic invocation failure");
        }
    }

    /** 普通 Item 即使旧 API 放行也不得适用。 */
    private static final class OrdinaryLegacyItem extends Item {
        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            return true;
        }
    }

    /** 只有相似简单名，不具备精确 FQCN 层级。 */
    private static final class HarvestToolLookalike extends Item {
        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            return true;
        }
    }
}
