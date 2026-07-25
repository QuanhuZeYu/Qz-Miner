package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 通用范围交互执行器必须 target-aware 且 fail-closed 的结构合同。 */
public class BlockInteractActionExecutorStructureTest {

    /** 每个目标使用当前手持检查保护与编辑权限，并只调用带目标坐标的 Forge 交互入口。 */
    @Test
    public void executorUsesPerTargetPermissionsAndTargetedActivationOnly() throws Exception {
        String source = readSource();
        int canExecuteStart = source.indexOf("public boolean canExecute(");
        int executeStart = source.indexOf("public boolean execute(", canExecuteStart);
        String canExecute = source.substring(canExecuteStart, executeStart);
        String execute = source.substring(executeStart);

        Assert.assertTrue(canExecute.contains("player.worldObj.blockExists(x, y, z)"));
        Assert.assertTrue(canExecute.contains("player.worldObj.canMineBlock(player, x, y, z)"));
        Assert.assertTrue(canExecute.contains("session.getRequest().getInteractFace()"));
        Assert.assertTrue(canExecute.contains("ItemStack currentStack = player.getCurrentEquippedItem()"));
        Assert.assertTrue(canExecute.contains("player.canPlayerEdit(x, y, z, face, currentStack)"));

        Assert.assertTrue("execute 必须为每个目标重新读取当前主手",
                execute.contains("ItemStack currentStack = player.getCurrentEquippedItem()"));
        Assert.assertTrue(execute.contains("activateBlockOrUseItem("));
        Assert.assertTrue(execute.contains("target.getX()"));
        Assert.assertTrue(execute.contains("target.getY()"));
        Assert.assertTrue(execute.contains("target.getZ()"));
        Assert.assertFalse("禁止恢复无目标坐标的空气右键 fallback", source.contains("tryUseItem("));
        Assert.assertTrue("模组异常必须 fail-closed",
                source.contains("catch (RuntimeException | LinkageError failure)"));
    }

    /** 交互后置必须在 finally 归一真实当前槽，并通过正常容器 listener 同步。 */
    @Test
    public void executorNormalizesAndSyncsCurrentSlotInFinally() throws Exception {
        String source = readSource();
        int executeStart = source.indexOf("public boolean execute(");
        String execute = source.substring(executeStart);
        int activation = execute.indexOf("activateBlockOrUseItem(");
        int postUseFinally = execute.indexOf("finally {", activation);
        int currentItem = execute.indexOf("int currentItem = player.inventory.currentItem;", postUseFinally);
        int currentStack = execute.indexOf(
                "ItemStack currentStackAfterUse = player.inventory.getCurrentItem();", currentItem);
        int nonPositive = execute.indexOf("currentStackAfterUse.stackSize <= 0", currentStack);
        int clearSlot = execute.indexOf("player.inventory.mainInventory[currentItem] = null;", nonPositive);
        int markDirty = execute.indexOf("player.inventory.markDirty();", clearSlot);
        int openContainerGuard = execute.indexOf("if (player.openContainer != null) {", markDirty);
        int sync = execute.indexOf("player.openContainer.detectAndSendChanges();", openContainerGuard);
        int syncCatch = execute.indexOf("catch (RuntimeException | LinkageError failure)", sync);
        int failClosed = execute.indexOf("interactionSucceeded = false;", syncCatch);

        Assert.assertTrue("后置归一必须位于交互调用后的 finally", activation >= 0
                && postUseFinally > activation && currentItem > postUseFinally);
        Assert.assertTrue("必须重新读取交互后的真实当前栈", currentStack > currentItem);
        Assert.assertTrue("只清理零或负数量的真实当前槽",
                nonPositive > currentStack && clearSlot > nonPositive);
        Assert.assertEquals("合法的正数量容器替换不得被额外清槽", clearSlot,
                execute.lastIndexOf("player.inventory.mainInventory[currentItem] = null;"));
        Assert.assertTrue("归一后必须标脏，并在容器非空时走正常 listener 同步",
                markDirty > clearSlot && openContainerGuard > markDirty && sync > openContainerGuard);
        Assert.assertFalse("禁止用数量变更标志抑制当前玩家的标准槽包",
                source.contains("isChangingQuantityOnly"));
        Assert.assertTrue("库存后置异常必须 fail-closed", syncCatch > sync && failClosed > syncCatch);
        Assert.assertFalse("禁止恢复无目标坐标的空气右键 fallback", source.contains("tryUseItem("));
    }

    private static String readSource() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/BlockInteractActionExecutor.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
