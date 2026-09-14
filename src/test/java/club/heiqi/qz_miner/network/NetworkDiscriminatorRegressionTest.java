package club.heiqi.qz_miner.network;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 5.3 严格版本冻结的 17 项 discriminator、packet class 与接收 Side 全表。
 *
 * <p><b>为什么不是源码文本快照</b>：本表是对外协议契约（网络兼容），守的是「第 N 号 discriminator 对应
 * 哪个 handler / packet / Side」。编号从<b>编译产物的常量</b>取（反射读 {@code NetworkMain.DISCRIMINATOR_}，
 * 改数值即真红），四元组从 {@code register()} 方法体内 {@code registerMessage(...)} 调用的
 * <b>实参清单</b>取（改 handler / packet / 常量名 / Side 即真红）。改换行、加注释、等价重排声明都不误报。</p>
 *
 * <p><b>守不到什么</b>：FML 是否真的把注册表装上需要 NetworkRegistry 运行时，纯 JVM 证不到；
 * 本用例只证「注册表内容与冻结表逐项一致」。注册顺序本就不构成 wire 契约（wire 由 discriminator 编号定），
 * 故这里不锁注册先后（追加顺序由 {@code AutoToolSwapNetworkRegistrationStructureTest} 单独守）。</p>
 */
public class NetworkDiscriminatorRegressionTest {

    private static final String NETWORK_MAIN_PATH =
            "src/main/java/club/heiqi/qz_miner/network/NetworkMain.java";

    private static final String[][] EXPECTED = {
            {"DISCRIMINATOR_KEY_STATE", "0", "PacketKeyState.KeyStatePacketHandler", "PacketKeyState", "SERVER"},
            {"DISCRIMINATOR_CHAIN_MODE_SWITCH", "1", "PacketChainModeSwitch.Handler", "PacketChainModeSwitch", "SERVER"},
            {"DISCRIMINATOR_CHAIN_SUB_MODE_SWITCH", "2", "PacketChainSubModeSwitch.Handler", "PacketChainSubModeSwitch", "SERVER"},
            {"DISCRIMINATOR_CHAIN_CONFIG_REQUEST", "3", "PacketChainConfigRequest.Handler", "PacketChainConfigRequest", "SERVER"},
            {"DISCRIMINATOR_OBJECT_GROUP_CONFIG_REQUEST", "4", "PacketObjectGroupConfigRequest.Handler", "PacketObjectGroupConfigRequest", "SERVER"},
            {"DISCRIMINATOR_LOOTGAMES_PREVIEW_REQUEST", "5", "PacketLootGamesMinesweeperPreviewRequest.Handler", "PacketLootGamesMinesweeperPreviewRequest", "SERVER"},
            {"DISCRIMINATOR_LOOTGAMES_PREVIEW_RESPONSE", "6", "PacketLootGamesMinesweeperPreviewResponse.Handler", "PacketLootGamesMinesweeperPreviewResponse", "CLIENT"},
            {"DISCRIMINATOR_CHAIN_PHASE_SNAPSHOT", "7", "PacketChainPhaseSnapshot.Handler", "PacketChainPhaseSnapshot", "CLIENT"},
            {"DISCRIMINATOR_CHAIN_CONFIG_SYNC", "8", "PacketChainConfigSync.Handler", "PacketChainConfigSync", "CLIENT"},
            {"DISCRIMINATOR_OBJECT_GROUP_CONFIG_SYNC", "9", "PacketObjectGroupConfigSync.Handler", "PacketObjectGroupConfigSync", "CLIENT"},
            {"DISCRIMINATOR_AUTO_TOOL_ROUND_START", "10", "PacketAutoToolSwapRoundStart.Handler", "PacketAutoToolSwapRoundStart", "SERVER"},
            {"DISCRIMINATOR_AUTO_TOOL_INTENT", "11", "PacketAutoToolSwapIntent.Handler", "PacketAutoToolSwapIntent", "SERVER"},
            {"DISCRIMINATOR_AUTO_TOOL_ROUND_RESULT", "12", "PacketAutoToolSwapRoundResult.Handler", "PacketAutoToolSwapRoundResult", "CLIENT"},
            {"DISCRIMINATOR_AUTO_TOOL_ACTION_RESULT", "13", "PacketAutoToolSwapActionResult.Handler", "PacketAutoToolSwapActionResult", "CLIENT"},
            {"DISCRIMINATOR_AUTO_TOOL_ROUND_PHASE", "14", "PacketAutoToolSwapRoundPhase.Handler", "PacketAutoToolSwapRoundPhase", "CLIENT"},
            {"DISCRIMINATOR_CUBOID_SELECTION_REQUEST", "15", "PacketCuboidSelectionRequest.Handler", "PacketCuboidSelectionRequest", "SERVER"},
            {"DISCRIMINATOR_CUBOID_SELECTION_SYNC", "16", "PacketCuboidSelectionSync.Handler", "PacketCuboidSelectionSync", "CLIENT"}
    };

    @Test
    public void explicitConstantsAndRegistrationsMatchTheCompleteFrozenTable() throws Exception {
        Map<String, Integer> constants = discriminatorConstants();
        Map<String, List<String>> registrations = registrations();

        Assert.assertEquals("冻结表必须恰好有 17 项 discriminator 常量", EXPECTED.length, constants.size());
        Assert.assertEquals("register() 必须恰好注册 17 个包", EXPECTED.length, registrations.size());
        Set<Integer> ids = new HashSet<Integer>();
        for (String[] row : EXPECTED) {
            String constant = row[0];
            int expectedId = Integer.parseInt(row[1]);
            Assert.assertEquals(constant + " 的 wire 编号已漂移",
                    Integer.valueOf(expectedId), constants.get(constant));
            Assert.assertTrue(constant + " 的 wire 编号与其它项重复",
                    ids.add(Integer.valueOf(expectedId)));

            List<String> registration = registrations.get(constant);
            Assert.assertNotNull("register() 缺少 " + constant + " 的注册项", registration);
            Assert.assertEquals(constant + " 的注册实参个数异常: " + registration, 4, registration.size());
            Assert.assertEquals(constant + " 注册的 handler 已漂移",
                    row[2], stripClassSuffix(registration.get(0)));
            Assert.assertEquals(constant + " 注册的 packet 已漂移",
                    row[3], stripClassSuffix(registration.get(1)));
            Assert.assertEquals(constant + " 注册的接收 Side 已漂移",
                    row[4], stripSide(registration.get(3)));
        }
        for (int id = 0; id < EXPECTED.length; id++) {
            Assert.assertTrue("missing discriminator " + id, ids.contains(Integer.valueOf(id)));
        }
    }

    /**
     * 从编译产物读 {@code DISCRIMINATOR_*} 常量值：源码文本里的数字改不改得对不由本方法负责，
     * 但产物里的编号一旦漂移，冻结表立刻红。
     */
    private static Map<String, Integer> discriminatorConstants() throws Exception {
        Map<String, Integer> constants = new LinkedHashMap<String, Integer>();
        for (Field field : NetworkMain.class.getDeclaredFields()) {
            if (!field.getName().startsWith("DISCRIMINATOR_") || field.getType() != int.class) {
                continue;
            }
            field.setAccessible(true);
            constants.put(field.getName(), Integer.valueOf(field.getInt(null)));
        }
        return constants;
    }

    /**
     * 取 {@code register()} 方法体内每个 {@code registerMessage(...)} 调用的实参清单，按 discriminator 常量名索引。
     * 同一常量注册两次直接判失败（唯一性契约）。
     */
    private static Map<String, List<String>> registrations() throws Exception {
        String body = JavaSourceSlices.methodBody(
                JavaSourceSlices.maskedMainSource(NETWORK_MAIN_PATH),
                "public void register()", "NetworkMain.register");
        Map<String, List<String>> byConstant = new LinkedHashMap<String, List<String>>();
        int from = 0;
        while (true) {
            int at = JavaSourceSlices.wordIndexOf(body, "registerMessage", from);
            if (at < 0) {
                return byConstant;
            }
            List<String> arguments = JavaSourceSlices.splitCallArguments(
                    body.substring(at), "registerMessage");
            Assert.assertEquals("注册调用必须恰好 4 个实参: " + arguments, 4, arguments.size());
            Assert.assertNull("同一 discriminator 不得注册两次: " + arguments.get(2),
                    byConstant.put(arguments.get(2), arguments));
            from = at + 1;
        }
    }

    private static String stripClassSuffix(String argument) {
        String value = argument.trim();
        return value.endsWith(".class") ? value.substring(0, value.length() - ".class".length()) : value;
    }

    private static String stripSide(String argument) {
        String value = argument.trim();
        return value.startsWith("Side.") ? value.substring("Side.".length()) : value;
    }
}
