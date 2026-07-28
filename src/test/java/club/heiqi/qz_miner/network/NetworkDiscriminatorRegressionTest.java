package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

/** 5.2 严格版本冻结的 17 项 discriminator、packet class 与接收 Side 全表。 */
public class NetworkDiscriminatorRegressionTest {

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
        String source = source();
        Map<String, Integer> constants = parseConstants(source);
        Map<String, String[]> registrations = parseRegistrations(source);

        Assert.assertEquals(17, constants.size());
        Assert.assertEquals(17, registrations.size());
        Set<Integer> ids = new HashSet<Integer>();
        for (String[] row : EXPECTED) {
            String constant = row[0];
            int expectedId = Integer.parseInt(row[1]);
            Assert.assertEquals(constant, Integer.valueOf(expectedId), constants.get(constant));
            Assert.assertTrue(constant, ids.add(Integer.valueOf(expectedId)));
            Assert.assertArrayEquals(constant,
                    new String[] {row[2], row[3], row[4]}, registrations.get(constant));
        }
        for (int id = 0; id < EXPECTED.length; id++) {
            Assert.assertTrue("missing discriminator " + id, ids.contains(Integer.valueOf(id)));
        }
        Assert.assertFalse(source.contains("packetId"));
        Assert.assertFalse(source.contains("++"));
    }

    private static Map<String, Integer> parseConstants(String source) {
        Pattern pattern = Pattern.compile(
                "private\\s+static\\s+final\\s+int\\s+(DISCRIMINATOR_[A-Z0-9_]+)\\s*=\\s*(\\d+)\\s*;");
        Matcher matcher = pattern.matcher(source);
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        while (matcher.find()) {
            Assert.assertNull(matcher.group(1), result.put(
                    matcher.group(1), Integer.valueOf(Integer.parseInt(matcher.group(2)))));
        }
        return result;
    }

    private static Map<String, String[]> parseRegistrations(String source) {
        Pattern pattern = Pattern.compile(
                "network\\.registerMessage\\(\\s*([A-Za-z0-9_.]+)\\.class,\\s*"
                + "([A-Za-z0-9_.]+)\\.class,\\s*(DISCRIMINATOR_[A-Z0-9_]+),\\s*"
                + "Side\\.(SERVER|CLIENT)\\s*\\);",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(source);
        Map<String, String[]> result = new HashMap<String, String[]>();
        while (matcher.find()) {
            Assert.assertNull(matcher.group(3), result.put(matcher.group(3),
                    new String[] {matcher.group(1), matcher.group(2), matcher.group(4)}));
        }
        return result;
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/NetworkMain.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
