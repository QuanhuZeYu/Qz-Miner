package club.heiqi.qz_miner.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;

/** 原版库存观察 Mixin 必须保持严格客户端分侧。 */
public class VanillaInventoryMixinBoundaryTest {
    @Test public void inventoryMixinsAreClientOnly() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/resources/mixins.qz_miner.json")), StandardCharsets.UTF_8);
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        JsonArray client = root.getAsJsonArray("client");
        for (String mixin : new String[] { "client.MixinC0EPacketClickWindow", "client.MixinNetHandlerPlayClientInventory" }) {
            Assert.assertTrue(contains(client, mixin));
        }
        Assert.assertFalse(new String(Files.readAllBytes(Paths.get("src/main/resources/mixins.qz_miner.early.json")),
                StandardCharsets.UTF_8).contains("Inventory"));
    }


    @Test public void inventoryResponsesAreObservedAfterVanillaAppliesThem() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/club/heiqi/qz_miner/mixins/client/MixinNetHandlerPlayClientInventory.java")),
                StandardCharsets.UTF_8);
        Assert.assertEquals(2, count(source, "at = @At(\"RETURN\")"));
        Assert.assertFalse(source.contains("at = @At(\"HEAD\")"));
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int position = 0; (position = value.indexOf(needle, position)) >= 0; position += needle.length()) count++;
        return count;
    }

    private static boolean contains(JsonArray values, String expected) {
        for (JsonElement value : values) if (expected.equals(value.getAsString())) return true;
        return false;
    }
}
