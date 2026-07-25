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

    /** 交互后置必须在 finally 委托共享库存支持，且同步失败继续 fail-closed。 */
    @Test
    public void executorDelegatesInventoryPostProcessingInFinally() throws Exception {
        String source = readSource();
        int executeStart = source.indexOf("public boolean execute(");
        String execute = source.substring(executeStart);
        int activation = execute.indexOf("activateBlockOrUseItem(");
        int postUseFinally = execute.indexOf("finally {", activation);
        int sharedSupport = execute.indexOf(
                "InteractionInventorySupport.normalizeAndSync(player, target)", postUseFinally);
        int failClosed = execute.indexOf("interactionSucceeded = false;", sharedSupport);

        Assert.assertTrue("后置归一必须位于交互调用后的 finally", activation >= 0
                && postUseFinally > activation && sharedSupport > postUseFinally);
        Assert.assertTrue("共享后置失败必须令本目标 fail-closed", failClosed > sharedSupport);
        Assert.assertFalse("generic executor 不得保留第二份库存后置实现",
                execute.contains("player.inventory.mainInventory[currentItem] = null;"));
        Assert.assertFalse("禁止用数量变更标志抑制当前玩家的标准槽包",
                source.contains("isChangingQuantityOnly"));
        Assert.assertFalse("禁止恢复无目标坐标的空气右键 fallback", source.contains("tryUseItem("));
    }

    private static String readSource() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/BlockInteractActionExecutor.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
