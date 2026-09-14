package club.heiqi.qz_miner.chain.executor;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 通用范围交互执行器必须 target-aware 且 fail-closed 的结构合同。
 *
 * <p>行为面为什么到不了这里：{@code canExecute}/{@code execute} 只接受 {@code EntityPlayerMP}，
 * 而 1.7.10 的 {@code EntityPlayerMP} 构造链要求 non-null {@code WorldServer}
 * （实测：{@code new EntityPlayerMP(null, null, profile, null)} 在 {@code Entity(World)} 里
 * 读 {@code world.isRemote} 直接 NPE），纯 JVM 构造不出玩家；本仓无 Mockito，也不为测试加 seam。
 * 因此这里只守「调用清单 / 位置关系 / 禁止调用面」这类结构契约，并在每条注释里写明它守不到什么。</p>
 */
public class BlockInteractActionExecutorStructureTest {

    private static final String SOURCE =
            "src/main/java/club/heiqi/qz_miner/chain/executor/BlockInteractActionExecutor.java";

    /** 每个目标使用当前手持检查保护与编辑权限，并只调用带目标坐标的 Forge 交互入口。 */
    @Test
    public void executorUsesPerTargetPermissionsAndTargetedActivationOnly() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String canExecute = JavaSourceSlices.methodBodyWithoutSignature(code, "canExecute");
        String execute = JavaSourceSlices.methodBodyWithoutSignature(code, "execute");

        int worldGate = JavaSourceSlices.wordIndexOf(canExecute, "blockExists");
        int mineGate = JavaSourceSlices.wordIndexOf(canExecute, "canMineBlock");
        int faceRead = JavaSourceSlices.wordIndexOf(canExecute, "getInteractFace");
        int editGate = JavaSourceSlices.wordIndexOf(canExecute, "canPlayerEdit");
        Assert.assertTrue("权限门必须查目标位置是否存在方块", worldGate >= 0);
        Assert.assertTrue("权限门必须查同世界挖掘权限", mineGate >= 0);
        Assert.assertTrue("权限门必须读请求里的交互面", faceRead >= 0);
        Assert.assertTrue("权限门必须做玩家编辑权限判定", editGate >= 0);
        Assert.assertTrue("编辑权限判定必须用真实当前手持",
                JavaSourceSlices.mentions(canExecute, "getCurrentEquippedItem"));

        // 激活入口的实参表就是「带目标坐标」契约本身：10 参的 Forge 签名 + 第 4~7 参为目标的 x/y/z 与请求交互面。
        List<String> activationArguments = JavaSourceSlices.splitCallArguments(execute, "activateBlockOrUseItem");
        Assert.assertEquals("必须恰好调用一次 10 参的 Forge 方块激活入口", 10, activationArguments.size());
        Assert.assertTrue("第 4 参必须是目标 X: " + activationArguments.get(3),
                activationArguments.get(3).contains("target.getX()"));
        Assert.assertTrue("第 5 参必须是目标 Y: " + activationArguments.get(4),
                activationArguments.get(4).contains("target.getY()"));
        Assert.assertTrue("第 6 参必须是目标 Z: " + activationArguments.get(5),
                activationArguments.get(5).contains("target.getZ()"));
        Assert.assertTrue("第 7 参必须是请求里的交互面: " + activationArguments.get(6),
                activationArguments.get(6).contains("getInteractFace()"));
        Assert.assertTrue("每个目标都必须重新读取当前主手",
                JavaSourceSlices.mentions(execute, "getCurrentEquippedItem"));

        // fail-closed：异常被吸收在 execute 内，且 catch 不得把失败翻成成功返回。
        Assert.assertTrue("模组异常必须在 execute 内被吸收", JavaSourceSlices.mentions(execute, "catch"));
        String catchBody = JavaSourceSlices.blockAfter(execute, "catch");
        Assert.assertFalse("模组异常必须 fail-closed，catch 内不得 return true", catchBody.contains("return true"));
        Assert.assertFalse("模组异常必须 fail-closed，catch 内不得重新抛出", JavaSourceSlices.mentions(catchBody, "throw"));
    }

    /** 交互后置必须在 finally 委托共享库存支持，且同步失败继续 fail-closed。 */
    @Test
    public void executorDelegatesInventoryPostProcessingInFinally() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String execute = JavaSourceSlices.methodBodyWithoutSignature(code, "execute");
        int activation = JavaSourceSlices.wordIndexOf(execute, "activateBlockOrUseItem");
        int postUseFinally = JavaSourceSlices.wordIndexOf(execute, "finally", activation);
        int sharedSupport = JavaSourceSlices.wordIndexOf(execute, "normalizeAndSync", postUseFinally);

        Assert.assertTrue("后置归一必须位于交互调用后的 finally", activation >= 0
                && postUseFinally > activation && sharedSupport > postUseFinally);
        // 共享后置返回 false 后本方法体内必须出现失败落值；只证明「结果能被翻成失败」，
        // 证明不了真的返回了 false（那需要可控玩家），故与上面的次序断言合起来才构成契约。
        Assert.assertTrue("共享后置失败必须令本目标 fail-closed",
                JavaSourceSlices.wordIndexOf(execute, "false", sharedSupport) > sharedSupport);
        Assert.assertFalse("禁止恢复无目标坐标的空气右键 fallback",
                CompiledClasses.references(BlockInteractActionExecutor.class, "tryUseItem"));
    }

    /** 空会话/空目标必须 fail-closed，且只声明服务 INTERACT 模式（这两条纯 JVM 可达）。 */
    @Test
    public void executorClaimsInteractModeOnlyAndRejectsEmptyInput() {
        BlockInteractActionExecutor executor = new BlockInteractActionExecutor();

        Assert.assertTrue(executor.supports(ChainMode.INTERACT));
        Assert.assertFalse(executor.supports(ChainMode.CHAIN));
        Assert.assertFalse("空玩家必须直接拒绝", executor.canExecute(null, null, null));
        Assert.assertFalse("空玩家必须直接拒绝", executor.execute(null, null, null));
    }
}
