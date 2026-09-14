package club.heiqi.qz_miner.chain.executor;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 共享交互库存后置必须保留零栈与标准 listener 合同。
 *
 * <p>行为面为什么到不了这里：{@code normalizeAndSync(EntityPlayerMP, ChainTarget)} 需要真实
 * {@code EntityPlayerMP}（含 {@code inventory}/{@code openContainer}），纯 JVM 构造不出玩家
 * （实测：{@code EntityPlayerMP} 构造链要求 non-null {@code WorldServer}）。因此这里守结构关系：
 * 归一推进次序、清槽唯一性、listener 同步的接收者，以及「本类不得自造库存实现」的编译产物调用面。</p>
 */
public class InteractionInventorySupportStructureTest {

    private static final String SUPPORT =
            "src/main/java/club/heiqi/qz_miner/chain/executor/InteractionInventorySupport.java";
    private static final String EXECUTOR =
            "src/main/java/club/heiqi/qz_miner/chain/executor/BlockInteractActionExecutor.java";

    /** 重新读取真实当前槽，只清非正数量，再标脏并走当前 openContainer listener。 */
    @Test
    public void supportNormalizesCurrentSlotBeforeStandardListenerSync() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SUPPORT);
        String body = JavaSourceSlices.methodBodyWithoutSignature(code, "normalizeAndSync");

        int slotRead = JavaSourceSlices.wordIndexOf(body, "currentItem");
        int stackReread = JavaSourceSlices.wordIndexOf(body, "getCurrentItem()");
        int sizeGate = JavaSourceSlices.wordIndexOf(body, "stackSize");
        int clearSlot = JavaSourceSlices.wordIndexOf(body, "mainInventory");
        int markDirty = JavaSourceSlices.wordIndexOf(body, "markDirty()");
        int containerGate = JavaSourceSlices.wordIndexOf(body, "openContainer");
        int listenerSync = JavaSourceSlices.wordIndexOf(body, "detectAndSendChanges()");

        Assert.assertTrue("必须先读当前槽号再重读使用后的当前栈",
                slotRead >= 0 && stackReread > slotRead);
        Assert.assertTrue("数量判定必须先于清槽", sizeGate >= 0 && clearSlot > sizeGate);
        Assert.assertEquals("当前槽只允许被清一次", 1, JavaSourceSlices.wordCount(body, "mainInventory"));
        Assert.assertTrue("次序必须是 清槽 -> 标脏 -> 打开容器门 -> 标准 listener 同步",
                markDirty > clearSlot && containerGate > markDirty && listenerSync > containerGate);
        // 不锚定「player.openContainer.xxx()」这种整链写法：只要同步发生在当前容器门内即可
        // （抽局部变量、换行、改名局部变量都不该误报；把同步搬出容器门才会红）。
        Assert.assertTrue("标准 listener 同步必须发生在当前容器门内",
                JavaSourceSlices.mentions(JavaSourceSlices.blockAfter(body, "if (player.openContainer != null)"),
                        "detectAndSendChanges"));
        Assert.assertTrue("成功路径先返回 true，异常路径在 catch 里",
                JavaSourceSlices.wordIndexOf(body, "return true") >= 0
                        && JavaSourceSlices.wordIndexOf(body, "catch") > JavaSourceSlices.wordIndexOf(body, "return true"));
        Assert.assertFalse("同步异常必须 fail-closed，catch 内不得 return true",
                JavaSourceSlices.blockAfter(body, "catch").contains("return true"));
    }

    /** generic executor 的 finally 必须只委托共享后置，自己不得再动库存或 listener。 */
    @Test
    public void genericExecutorUsesSharedSupport() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(EXECUTOR);
        String execute = JavaSourceSlices.methodBodyWithoutSignature(code, "execute");
        int activation = JavaSourceSlices.wordIndexOf(execute, "activateBlockOrUseItem");
        int finallyBlock = JavaSourceSlices.wordIndexOf(execute, "finally", activation);
        int shared = JavaSourceSlices.wordIndexOf(execute, "normalizeAndSync", finallyBlock);

        Assert.assertTrue("共享后置必须在交互调用后的 finally 内被调用", activation >= 0
                && finallyBlock > activation && shared > finallyBlock);
        Assert.assertFalse("库存归一只有一个实现点，generic executor 不得直接写主背包",
                CompiledClasses.references(BlockInteractActionExecutor.class, "mainInventory"));
        Assert.assertFalse("generic executor 不得自己同步容器",
                CompiledClasses.references(BlockInteractActionExecutor.class, "detectAndSendChanges"));
        Assert.assertFalse("generic executor 不得抑制标准当前槽 listener",
                CompiledClasses.references(BlockInteractActionExecutor.class, "isChangingQuantityOnly"));
    }
}
