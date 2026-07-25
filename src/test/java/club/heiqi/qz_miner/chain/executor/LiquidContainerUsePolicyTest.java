package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 注册容器与动态流体容器的无提交准入矩阵。 */
public class LiquidContainerUsePolicyTest {

    /** registered empty 可接收，filled/null/未知均拒绝，且只向 probe 交副本。 */
    @Test
    public void registeredContainersRequireEmptyFillableVariant() {
        ItemStack empty = stack(0, "empty");
        RecordingProbe emptyProbe = new RecordingProbe(true, 0, false);
        ItemStack filled = stack(1000, "filled");
        RecordingProbe filledProbe = new RecordingProbe(false, 0, false);

        Assert.assertTrue(LiquidContainerUsePolicy.canAccept(empty, emptyProbe));
        Assert.assertNotSame(empty, emptyProbe.registeredStack);
        assertUnchanged(empty, 0, "empty");
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept(filled, filledProbe));
        assertUnchanged(filled, 1000, "filled");
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept((ItemStack) null, emptyProbe));
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept("", empty));
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept(null, empty));
    }

    /** 动态容器 empty/partial 可接收，full 拒绝，模拟对副本的改写不得污染真实栈。 */
    @Test
    public void dynamicContainerSimulationAcceptsOnlyPositiveCapacityWithoutMutation() {
        ItemStack empty = stack(0, "empty");
        ItemStack partial = stack(400, "partial");
        ItemStack full = stack(1000, "full");

        RecordingProbe emptyProbe = new RecordingProbe(false, 1000, false);
        RecordingProbe partialProbe = new RecordingProbe(false, 600, false);
        RecordingProbe fullProbe = new RecordingProbe(false, 0, false);
        Assert.assertTrue(LiquidContainerUsePolicy.canAccept(empty, emptyProbe));
        Assert.assertTrue(LiquidContainerUsePolicy.canAccept(partial, partialProbe));
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept(full, fullProbe));

        Assert.assertNotSame(empty, emptyProbe.dynamicStack);
        Assert.assertNotSame(partial, partialProbe.dynamicStack);
        Assert.assertNotSame(full, fullProbe.dynamicStack);
        assertUnchanged(empty, 0, "empty");
        assertUnchanged(partial, 400, "partial");
        assertUnchanged(full, 1000, "full");
    }

    /** 第三方探测异常与非正栈均 fail-closed，真实栈保持原样。 */
    @Test
    public void probeFailuresAreFailClosedAndNonMutating() {
        ItemStack throwing = stack(250, "throwing");
        ItemStack exhausted = stack(0, "exhausted");
        exhausted.stackSize = 0;

        Assert.assertFalse(LiquidContainerUsePolicy.canAccept(
                throwing, new RecordingProbe(false, 750, true)));
        assertUnchanged(throwing, 250, "throwing");
        Assert.assertFalse(LiquidContainerUsePolicy.canAccept(
                exhausted, new RecordingProbe(true, 1000, false)));
        Assert.assertEquals(0, exhausted.stackSize);
        Assert.assertEquals("exhausted", exhausted.getTagCompound().getString("marker"));
    }

    /** 生产接缝必须解析注册流体，并只调用副本上的 registered fill 或 doFill=false。 */
    @Test
    public void productionProbeUsesForgeSimulationOnly() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/LiquidContainerUsePolicy.java").toPath()),
                StandardCharsets.UTF_8);

        Assert.assertTrue(source.contains("FluidRegistry.getFluid(seedFluidName)"));
        Assert.assertTrue(source.contains("if (fluid == null)"));
        Assert.assertTrue(source.contains("ItemStack simulatedStack = currentStack.copy();"));
        Assert.assertTrue(source.contains("FluidContainerRegistry.isEmptyContainer(simulatedStack)"));
        Assert.assertTrue(source.contains("FluidContainerRegistry.fillFluidContainer(probe, simulatedStack)"));
        Assert.assertTrue(source.contains("item instanceof IFluidContainerItem"));
        Assert.assertTrue(source.contains(
                "((IFluidContainerItem) item).fill(simulatedStack, probe, false)"));
        Assert.assertFalse(source.contains(
                "((IFluidContainerItem) item).fill(simulatedStack, probe, true)"));
    }

    private static ItemStack stack(int stored, String marker) {
        ItemStack stack = new ItemStack(new Item(), 1, stored);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("marker", marker);
        stack.setTagCompound(tag);
        return stack;
    }

    private static void assertUnchanged(ItemStack stack, int stored, String marker) {
        Assert.assertEquals(1, stack.stackSize);
        Assert.assertEquals(stored, stack.getItemDamage());
        Assert.assertNotNull(stack.getTagCompound());
        Assert.assertEquals(marker, stack.getTagCompound().getString("marker"));
    }

    /** 可控的两类容器探测，动态分支故意改写收到的副本。 */
    private static final class RecordingProbe implements LiquidContainerUsePolicy.ContainerProbe {
        private final boolean registeredFillable;
        private final int dynamicAccepted;
        private final boolean throwOnProbe;
        private ItemStack registeredStack;
        private ItemStack dynamicStack;

        private RecordingProbe(boolean registeredFillable, int dynamicAccepted, boolean throwOnProbe) {
            this.registeredFillable = registeredFillable;
            this.dynamicAccepted = dynamicAccepted;
            this.throwOnProbe = throwOnProbe;
        }

        @Override
        public boolean canFillRegisteredContainer(ItemStack simulatedStack) {
            registeredStack = simulatedStack;
            return registeredFillable;
        }

        @Override
        public int simulateDynamicFill(ItemStack simulatedStack) {
            dynamicStack = simulatedStack;
            simulatedStack.stackSize = 99;
            simulatedStack.setItemDamage(Integer.MAX_VALUE);
            simulatedStack.getTagCompound().setString("marker", "mutated-copy");
            if (throwOnProbe) {
                throw new IllegalStateException("synthetic fill simulation failure");
            }
            return dynamicAccepted;
        }
    }
}
