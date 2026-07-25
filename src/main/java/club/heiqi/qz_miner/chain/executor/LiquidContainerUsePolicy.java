package club.heiqi.qz_miner.chain.executor;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/**
 * 当前手持容器能否以正常物品语义接收冻结 seed 流体的无提交策略。
 */
final class LiquidContainerUsePolicy {

    private LiquidContainerUsePolicy() {}

    /**
     * 仅在副本上模拟当前容器接收一个 source block 容量的目标流体。
     *
     * @param seedFluidName 冻结 seed 的注册流体名
     * @param currentStack 当前槽真实栈，只读
     * @return 当前栈是否可接收正量目标流体
     */
    static boolean canAccept(String seedFluidName, ItemStack currentStack) {
        if (seedFluidName == null || seedFluidName.isEmpty()
                || currentStack == null || currentStack.stackSize <= 0) {
            return false;
        }
        try {
            Fluid fluid = FluidRegistry.getFluid(seedFluidName);
            if (fluid == null) {
                return false;
            }
            return canAccept(currentStack, new ForgeContainerProbe(
                    new FluidStack(fluid, FluidContainerRegistry.BUCKET_VOLUME)));
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 包级纯值接缝：统一副本与异常合同，并让 headless 测试无需启动 FML registry。 */
    static boolean canAccept(ItemStack currentStack, ContainerProbe probe) {
        if (currentStack == null || currentStack.stackSize <= 0 || probe == null) {
            return false;
        }
        try {
            ItemStack simulatedStack = currentStack.copy();
            return probe.canFillRegisteredContainer(simulatedStack)
                    || probe.simulateDynamicFill(simulatedStack) > 0;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** 当前容器副本的两类无提交探测。 */
    interface ContainerProbe {

        boolean canFillRegisteredContainer(ItemStack simulatedStack);

        int simulateDynamicFill(ItemStack simulatedStack);
    }

    /** Forge 注册容器与 IFluidContainerItem 的生产探测实现。 */
    private static final class ForgeContainerProbe implements ContainerProbe {
        private final FluidStack probe;

        private ForgeContainerProbe(FluidStack probe) {
            this.probe = probe;
        }

        @Override
        public boolean canFillRegisteredContainer(ItemStack simulatedStack) {
            return FluidContainerRegistry.isEmptyContainer(simulatedStack)
                    && FluidContainerRegistry.fillFluidContainer(probe, simulatedStack) != null;
        }

        @Override
        public int simulateDynamicFill(ItemStack simulatedStack) {
            Item item = simulatedStack.getItem();
            return item instanceof IFluidContainerItem
                    ? ((IFluidContainerItem) item).fill(simulatedStack, probe, false)
                    : 0;
        }
    }
}
