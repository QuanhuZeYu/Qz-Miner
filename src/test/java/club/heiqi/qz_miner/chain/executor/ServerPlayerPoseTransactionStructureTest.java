package club.heiqi.qz_miner.chain.executor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 虚拟玩家姿态事务的字段全集、精确碰撞盒与幂等恢复合同。
 *
 * <p>行为面为什么到不了这里：{@code capture(EntityPlayerMP)} 只接受服务端玩家，而 1.7.10 的
 * {@code EntityPlayerMP} 构造链要求 non-null {@code WorldServer}（实测 NPE 于 {@code Entity(World)}
 * 读 {@code world.isRemote}），纯 JVM 构造不出玩家，也不为测试新增 stub 玩家 seam。
 * 因此这里守的是「捕获集合 = 改写集合 = 恢复集合」这类字段级一致性契约：把「改了却没捕获/
 * 没恢复」的回归钉死，同时不再逐条匹配赋值语句文本。</p>
 */
public class ServerPlayerPoseTransactionStructureTest {

    private static final String SOURCE =
            "src/main/java/club/heiqi/qz_miner/chain/executor/ServerPlayerPoseTransaction.java";

    private static final List<String> BOUNDS_FIELDS = Arrays.asList(
            "originalMinX", "originalMinY", "originalMinZ",
            "originalMaxX", "originalMaxY", "originalMaxZ");

    /** current/prev/last/rotation 与 bounding box 六坐标必须全部捕获。 */
    @Test
    public void transactionCapturesEveryMutatedFieldAndExactBoundingBox() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String constructor = JavaSourceSlices.methodBodyWithoutSignature(code, "ServerPlayerPoseTransaction");
        String apply = JavaSourceSlices.methodBodyWithoutSignature(code, "apply");
        String close = JavaSourceSlices.methodBodyWithoutSignature(code, "close");

        List<String> mutated = JavaSourceSlices.assignedFields(apply, "player");
        List<String> restored = JavaSourceSlices.assignedFields(close, "player");
        Assert.assertFalse("apply 必须真的改写玩家姿态字段", mutated.isEmpty());
        Assert.assertEquals("close 必须恢复 apply 改写的全部字段（一处不漏、一处不多）", mutated, restored);
        for (String field : mutated) {
            String captured = "original" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Assert.assertTrue("必须声明捕获字段: " + captured, declares(captured));
            String value = JavaSourceSlices.assignedValue(constructor, "this", captured);
            Assert.assertEquals("捕获必须取自对应来源: " + captured, field, lastSegmentOf(value));
        }

        List<String> applyBounds = JavaSourceSlices.splitCallArguments(apply, "setBounds");
        Assert.assertEquals("apply 必须用六坐标扩写碰撞盒", 6, applyBounds.size());
        List<String> restoreBounds = JavaSourceSlices.splitCallArguments(close, "setBounds");
        Assert.assertEquals("close 必须用六坐标还原碰撞盒", 6, restoreBounds.size());
        for (int i = 0; i < BOUNDS_FIELDS.size(); i++) {
            String captured = BOUNDS_FIELDS.get(i);
            Assert.assertTrue("必须声明碰撞盒捕获字段: " + captured, declares(captured));
            String source = captured.substring("original".length());
            source = Character.toLowerCase(source.charAt(0)) + source.substring(1);
            String value = JavaSourceSlices.assignedValue(constructor, "this", captured);
            Assert.assertEquals("构造器必须捕获碰撞盒坐标: " + captured, source, lastSegmentOf(value));
            Assert.assertTrue("apply 必须以捕获坐标扩写: " + applyBounds.get(i),
                    applyBounds.get(i).contains(captured));
            Assert.assertTrue("close 必须以捕获坐标还原: " + restoreBounds.get(i),
                    restoreBounds.get(i).contains(captured));
        }
    }

    /** 应用必须统一 current/prev/last/rotation；关闭必须幂等且尽最大努力恢复。 */
    @Test
    public void transactionAppliesCompletePoseAndRestoresIdempotently() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String close = JavaSourceSlices.methodBodyWithoutSignature(code, "close");

        int guard = JavaSourceSlices.wordIndexOf(close, "closed");
        int markClosed = JavaSourceSlices.wordIndexOf(close, "closed", guard + 1);
        int firstRestore = JavaSourceSlices.wordIndexOf(close, "player.");
        Assert.assertTrue("close 必须先判定幂等位", guard >= 0 && markClosed > guard);
        Assert.assertTrue("必须先置幂等位再恢复，恢复中途重入不得二次改写玩家",
                firstRestore > markClosed);
        // 5 组恢复 = 坐标 / 上一帧坐标 / 上 tick 坐标 / 旋转 / 碰撞盒；每组独立守卫才叫 best-effort。
        Assert.assertEquals("每组恢复都必须独立守卫", 5, JavaSourceSlices.wordCount(close, "catch"));
        Assert.assertEquals("每组失败都必须并入同一 best-effort 累加（不是静默吞掉）",
                5, JavaSourceSlices.wordCount(close, "mergeFailure("));
        Assert.assertTrue("恢复失败必须在最后抛回调用方，而不是留在日志里",
                JavaSourceSlices.mentions(close, "throw"));
    }

    /** 虚拟姿态只改本地状态：编译产物不得引用位置发布与网络发送 API。 */
    @Test
    public void virtualPoseNeverPublishesToClient() {
        Assert.assertFalse("虚拟姿态不得向客户端发布位置",
                CompiledClasses.references(ServerPlayerPoseTransaction.class, "setPositionAndUpdate"));
        Assert.assertFalse("虚拟姿态不得发网络包",
                CompiledClasses.references(ServerPlayerPoseTransaction.class, "sendPacket"));
    }

    /** 赋值右值的末段标识符（如 {@code player.prevPosX} -> {@code prevPosX}）。 */
    private static String lastSegmentOf(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static boolean declares(String fieldName) {
        for (Field field : ServerPlayerPoseTransaction.class.getDeclaredFields()) {
            if (fieldName.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }
}
