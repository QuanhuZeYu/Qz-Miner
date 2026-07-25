package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 共享交互库存后置必须保留零栈与标准 listener 合同。 */
public class InteractionInventorySupportStructureTest {

    /** 重新读取真实当前槽，只清非正数量，再标脏并走当前 openContainer listener。 */
    @Test
    public void supportNormalizesCurrentSlotBeforeStandardListenerSync() throws Exception {
        String source = readSource("InteractionInventorySupport.java");
        int currentItem = source.indexOf("int currentItem = player.inventory.currentItem;");
        int currentStack = source.indexOf(
                "ItemStack currentStackAfterUse = player.inventory.getCurrentItem();", currentItem);
        int nonPositive = source.indexOf("currentStackAfterUse.stackSize <= 0", currentStack);
        int clearSlot = source.indexOf("player.inventory.mainInventory[currentItem] = null;", nonPositive);
        int markDirty = source.indexOf("player.inventory.markDirty();", clearSlot);
        int openContainer = source.indexOf("if (player.openContainer != null) {", markDirty);
        int sync = source.indexOf("player.openContainer.detectAndSendChanges();", openContainer);
        int success = source.indexOf("return true;", sync);
        int failureCatch = source.indexOf("catch (RuntimeException | LinkageError failure)", success);
        int failClosed = source.indexOf("return false;", failureCatch);

        Assert.assertTrue(currentItem >= 0 && currentStack > currentItem);
        Assert.assertTrue(nonPositive > currentStack && clearSlot > nonPositive);
        Assert.assertEquals("合法替换栈不得被额外清槽", clearSlot,
                source.lastIndexOf("player.inventory.mainInventory[currentItem] = null;"));
        Assert.assertTrue(markDirty > clearSlot && openContainer > markDirty && sync > openContainer);
        Assert.assertTrue("同步异常必须 fail-closed", failureCatch > success && failClosed > failureCatch);
        Assert.assertFalse("不得抑制标准当前槽 listener", source.contains("isChangingQuantityOnly"));
    }

    /** generic executor 的 finally 必须只委托共享后置。 */
    @Test
    public void genericExecutorUsesSharedSupport() throws Exception {
        String source = readSource("BlockInteractActionExecutor.java");
        int activation = source.indexOf("activateBlockOrUseItem(");
        int finallyBlock = source.indexOf("finally {", activation);
        int shared = source.indexOf(
                "InteractionInventorySupport.normalizeAndSync(player, target)", finallyBlock);

        Assert.assertTrue(activation >= 0 && finallyBlock > activation && shared > finallyBlock);
        Assert.assertFalse(source.contains("detectAndSendChanges()"));
    }

    private static String readSource(String fileName) throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/" + fileName).toPath()),
                StandardCharsets.UTF_8);
    }
}
