package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 服务端 Minecraft 库存适配的结构边界。
 *
 * <p>原用例把「读了哪几个 live 字段」「先标脏再重发」写成整文件 {@code contains}：
 * 等价重构（抽局部变量、换调用写法）即误报，而把实现换成缓存读却仍可能绿。现在改为
 * ①方法体切片 + 词边界标识符（读的是哪几个 live 事实、调用先后、实参来源）
 * ②编译产物的成员引用面（不得走增量同步 / 槽位点击 / 网络包直发）。</p>
 *
 * <p><b>为什么不行为化</b>：三门前置条件读的是 {@code Container}，而 1.7.10 的 Container
 * 是抽象类，headless 无法造实例（实测编译期即报「Container 是抽象的；无法实例化」），
 * 裁定 A 又不允许为测试新增 fixture / seam。</p>
 */
public class MinecraftAutoToolSwapInventoryPortStructureTest {

    private static final String PORT_SOURCE =
            "src/main/java/club/heiqi/qz_miner/toolswap/server/MinecraftAutoToolSwapInventoryPort.java";

    /** 适配必须分离直接 mutation 与可重复原版完整库存同步。 */
    @Test
    public void portUsesOnlyAllowedInventoryAndSynchronizationWiring() throws Exception {
        String masked = JavaSourceSlices.maskedMainSource(PORT_SOURCE);

        String windowGate = JavaSourceSlices.methodBodyWithoutSignature(masked, "hasPersonalInventoryWindow0");
        JavaSourceSlices.assertContains(windowGate, "player.openContainer",
                "个人库存门必须读 live openContainer");
        JavaSourceSlices.assertContains(windowGate, "player.inventoryContainer.windowId",
                "个人库存门必须核对 live window 0");

        String cursorGate = JavaSourceSlices.methodBodyWithoutSignature(masked, "isCursorEmpty");
        JavaSourceSlices.assertContains(cursorGate, "player.inventory.getItemStack()",
                "cursor 门必须读 live cursor 槽");
        JavaSourceSlices.assertContains(cursorGate, "null",
                "cursor 门必须按 null 判定空手");

        String sync = JavaSourceSlices.methodBodyWithoutSignature(masked, "syncInventoryDifference");
        JavaSourceSlices.assertBefore(sync, "player.inventory.markDirty()",
                "player.sendContainerToPlayer(", "publication 必须先标脏再重发完整库存");
        JavaSourceSlices.assertContains(
                JavaSourceSlices.callArguments(sync, "player.sendContainerToPlayer", "完整库存 publication"),
                "inventoryContainer",
                "publication 必须重发个人 inventory 容器，而不是当前打开的任意容器");

        JavaSourceSlices.assertAbsent(
                JavaSourceSlices.methodBodyWithoutSignature(masked, "swapInventorySlotsAtomically"),
                "markDirty", "swap 只做 mutation，不得触发 publication");
        JavaSourceSlices.assertAbsent(
                JavaSourceSlices.methodBodyWithoutSignature(masked, "rotateInventorySlotsAtomically"),
                "markDirty", "三槽轮转只做 mutation，不得触发 publication");

        for (String forbidden : new String[] { "detectAndSendChanges", "slotClick", "windowClick", "S2F" }) {
            Assert.assertFalse("编译产物不得出现被禁库存路径 " + forbidden,
                    CompiledClasses.references(MinecraftAutoToolSwapInventoryPort.class, forbidden));
        }
        // 已删除 contains("currentItem =")：常量池无法区分字段读/写，而本类合法地读 currentItem
        // （selectedHotbarSlot）；该文本片段既挡不住「换种写法继续越权改选中槽」，又会被等价赋值写法误报。
        // 已删除 contains("player.inventory.mainInventory")：换成 getStackInSlot(i) 语义等价却误报，
        // 换位语义已由 MinecraftAutoToolSwapInventoryPortTest 的引用/多集行为用例覆盖。
    }
}
