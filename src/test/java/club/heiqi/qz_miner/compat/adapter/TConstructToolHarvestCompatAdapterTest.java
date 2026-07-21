package club.heiqi.qz_miner.compat.adapter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import tconstruct.library.tools.HarvestTool;

/** TiC 工具类形识别、旧式虚调用与隔离边界。 */
public class TConstructToolHarvestCompatAdapterTest {

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

    /** 生产适配器只保留唯一 TiC FQCN，且不加载或反射解析可选类型。 */
    @Test
    public void sourceHasSingleOptionalNameAndNoTypeLoadingOrMemberReflection() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/compat/adapter/TConstructToolHarvestCompatAdapter.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertEquals(1, occurrences(source, "tconstruct.library.tools.HarvestTool"));
        Assert.assertFalse(source.contains("import tconstruct"));
        Assert.assertFalse(source.contains("Class.forName"));
        Assert.assertFalse(source.contains("resolveClass"));
        Assert.assertFalse(source.contains("getMethod"));
    }

    private ToolHarvestCompatAdapter.Result evaluate(Item item) {
        return adapter.evaluate(item, new ItemStack(item), target);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
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
