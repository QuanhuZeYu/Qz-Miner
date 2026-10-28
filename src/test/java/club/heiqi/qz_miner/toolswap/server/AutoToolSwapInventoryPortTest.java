package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/**
 * 验证库存端口的纯 Java 依赖边界及公开 void 库存 API 签名。
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
