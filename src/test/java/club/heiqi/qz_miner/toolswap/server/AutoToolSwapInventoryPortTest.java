package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/**
 * 库存端口保持纯 Java 边界的结构合同。
 *
 * <p>断言对象是编译产物的常量池与接口方法签名：被禁类型一旦真的被链接就红，
 * 注释措辞、排版与局部变量改名都不影响判定。原先对本接口 javadoc 中文措辞的四条断言
 * （「交换已经应用」「不得通过再次调用本方法来重试交换」「完整个人库存 publication」「三槽引用轮转」）
 * 已删除：改注释即误报、删掉实现却仍可能绿，守不到任何回归；其语义由下面的签名反射
 * 与调用方（批次服务 / 库存适配）的行为用例承担。</p>
 */
public class AutoToolSwapInventoryPortTest {

    private static final String PORT_TYPE = "club/heiqi/qz_miner/toolswap/server/AutoToolSwapInventoryPort";

    /** 端口必须保持纯 Java 边界，且三个库存 API 都是无返回值的公开方法。 */
    @Test
    public void portStaysPureJavaBoundaryAndExposesVoidInventoryApis() throws Exception {
        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(PORT_TYPE));
        for (String forbidden : new String[] { "net/minecraft/", "cpw/mods/", "io/netty/",
                "club/heiqi/qz_miner/client/toolswap" }) {
            Assert.assertFalse("端口不得链接 " + forbidden + " 类型：" + refs.classRefs,
                    refs.hasClassRefUnder(forbidden));
        }

        Assert.assertEquals("交换必须是 void 公开 API", Void.TYPE,
                AutoToolSwapInventoryPort.class.getMethod(
                        "swapInventorySlotsAtomically", Integer.TYPE, Integer.TYPE).getReturnType());
        Assert.assertEquals("三槽轮转必须是 void 公开 API", Void.TYPE,
                AutoToolSwapInventoryPort.class.getMethod(
                        "rotateInventorySlotsAtomically", Integer.TYPE, Integer.TYPE, Integer.TYPE).getReturnType());
        Assert.assertEquals("完整库存 publication 必须是无参 void 方法", Void.TYPE,
                AutoToolSwapInventoryPort.class.getMethod("syncInventoryDifference").getReturnType());
    }
}
