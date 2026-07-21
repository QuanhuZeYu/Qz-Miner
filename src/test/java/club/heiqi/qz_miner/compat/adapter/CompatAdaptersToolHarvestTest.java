package club.heiqi.qz_miner.compat.adapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 工具采掘适配器 registry 的四态终裁合同。 */
public class CompatAdaptersToolHarvestTest {

    private final Item item = new Item();
    private final ItemStack stack = new ItemStack(item);
    private final Block target = new TestBlock();

    /** 首个非 NOT_APPLICABLE 结果终裁，后续适配器不得求值。 */
    @Test
    public void firstApplicableResultIsTerminal() {
        AtomicInteger laterCalls = new AtomicInteger();
        ToolHarvestCompatAdapter notApplicable = returning(ToolHarvestCompatAdapter.Result.NOT_APPLICABLE, null);
        ToolHarvestCompatAdapter deny = returning(ToolHarvestCompatAdapter.Result.DENY, null);
        ToolHarvestCompatAdapter laterAllow = returning(ToolHarvestCompatAdapter.Result.ALLOW, laterCalls);

        Assert.assertEquals(ToolHarvestCompatAdapter.Result.DENY,
                evaluate(Arrays.asList(notApplicable, deny, laterAllow)));
        Assert.assertEquals(0, laterCalls.get());
    }

    /** 无适用项稳定返回 NOT_APPLICABLE。 */
    @Test
    public void allNotApplicableRemainNotApplicable() {
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.NOT_APPLICABLE,
                evaluate(Collections.singletonList(
                        returning(ToolHarvestCompatAdapter.Result.NOT_APPLICABLE, null))));
    }

    /** UNRESOLVED、异常、null 结果与 null adapter 都必须立即 fail-closed。 */
    @Test
    public void unresolvedFailureAndNullAreTerminal() {
        ToolHarvestCompatAdapter allow = returning(ToolHarvestCompatAdapter.Result.ALLOW, null);
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                evaluate(Arrays.asList(returning(ToolHarvestCompatAdapter.Result.UNRESOLVED, null), allow)));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                evaluate(Arrays.asList(throwing(), allow)));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                evaluate(Arrays.asList(returning(null, null), allow)));
        Assert.assertEquals(ToolHarvestCompatAdapter.Result.UNRESOLVED,
                evaluate(Arrays.asList(null, allow)));
    }

    private ToolHarvestCompatAdapter.Result evaluate(java.util.List<ToolHarvestCompatAdapter> adapters) {
        return CompatAdapters.evaluateToolHarvest(item, stack, target, adapters);
    }

    private static ToolHarvestCompatAdapter returning(final ToolHarvestCompatAdapter.Result result,
            final AtomicInteger calls) {
        return new ToolHarvestCompatAdapter() {
            @Override
            public Result evaluate(Item item, ItemStack stack, Block target) {
                if (calls != null) {
                    calls.incrementAndGet();
                }
                return result;
            }
        };
    }

    private static ToolHarvestCompatAdapter throwing() {
        return new ToolHarvestCompatAdapter() {
            @Override
            public Result evaluate(Item item, ItemStack stack, Block target) {
                throw new IllegalStateException("synthetic adapter failure");
            }
        };
    }

    /** 测试目标。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
