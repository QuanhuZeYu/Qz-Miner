package club.heiqi.qz_miner.client.picker;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.ui.editor.SearchPickerData;

/** Picker Codec 的无状态完整列表更新测试。 */
public class ObjectGroupPickerCodecTest {
    @Test
    public void decodesAllSingleAndMultipleWithoutRejectingRawMembers() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        Assert.assertEquals(SearchPickerData.SelectionMode.ALL,
                codec.decode(Arrays.<Object>asList(Integer.valueOf(1), "minecraft:log@*")).mode());
        Assert.assertEquals(SearchPickerData.SelectionMode.SELECTED,
                codec.decode(Collections.singletonList("minecraft:log@4")).mode());
        SearchPickerData.Selection multiple = codec.decode(Collections.singletonList("minecraft:log@[8,4]"));
        Assert.assertEquals(SearchPickerData.SelectionMode.SELECTED, multiple.mode());
        Assert.assertEquals(Arrays.asList("minecraft:log@4", "minecraft:log@8"), multiple.variantKeys());
    }

    @Test
    public void replacesWildcardWithSingleAndMultiple() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        Assert.assertEquals(Collections.singletonList("minecraft:log@4"), codec.encode(
                Collections.singletonList("minecraft:log@*"), single("minecraft:log", 4)));
        Assert.assertEquals(Collections.singletonList("minecraft:log@[4,8]"), codec.encode(
                Collections.singletonList("minecraft:log@*"), multiple("minecraft:log", 8, 4)));
    }

    @Test
    public void replacesSpecificWithWildcard() {
        Assert.assertEquals(Collections.singletonList("minecraft:log@*"), new ObjectGroupPickerCodec().encode(
                Arrays.asList("minecraft:log@0", "minecraft:log@4"), all("minecraft:log")));
    }

    @Test
    public void normalizesDuplicateRegistryAtFirstPositionAndPreservesAllOtherRawValues() {
        Object marker = Integer.valueOf(7);
        Assert.assertEquals(Arrays.<Object>asList("bad raw", "minecraft:log@8", "other:block@2", marker),
                new ObjectGroupPickerCodec().encode(
                        Arrays.<Object>asList("bad raw", "minecraft:log@0", "other:block@2", marker,
                                "minecraft:log@4"),
                        single("minecraft:log", 8)));
    }

    @Test
    public void appendsWhenRegistryIsAbsent() {
        Assert.assertEquals(Arrays.asList("other:block@2", "minecraft:log@4"),
                new ObjectGroupPickerCodec().encode(Collections.singletonList("other:block@2"),
                        single("minecraft:log", 4)));
    }

    @Test
    public void interleavedRowsOnSameCodecNeverShareState() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        codec.decode(Collections.singletonList("first:block@0"));
        codec.decode(Collections.singletonList("second:block@1"));
        Assert.assertEquals(Collections.singletonList("first:block@2"),
                codec.encode(Collections.singletonList("first:block@0"), single("first:block", 2)));
        Assert.assertEquals(Collections.singletonList("second:block@3"),
                codec.encode(Collections.singletonList("second:block@1"), single("second:block", 3)));
    }

    @Test
    public void unenumeratedSelectedKeysRoundTripWithoutLoss() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        SearchPickerData.Selection decoded = codec.decode(Collections.singletonList("minecraft:log@[4,8]"));
        Assert.assertEquals(SearchPickerData.SelectionMode.SELECTED, decoded.mode());
        Assert.assertEquals(Collections.singletonList("minecraft:log@[4,8]"),
                codec.encode(Collections.singletonList("minecraft:log@[4,8]"), decoded));
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeRequiresCurrentList() {
        new ObjectGroupPickerCodec().encode("not a list", all("minecraft:log"));
    }

    private static SearchPickerData.Selection all(String registry) {
        return new SearchPickerData.Selection(registry, SearchPickerData.SelectionMode.ALL,
                Collections.<String>emptyList());
    }

    private static SearchPickerData.Selection single(String registry, int metadata) {
        return new SearchPickerData.Selection(registry, SearchPickerData.SelectionMode.SELECTED,
                Collections.singletonList(registry + "@" + metadata));
    }

    private static SearchPickerData.Selection multiple(String registry, int... metadata) {
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (int value : metadata) keys.add(registry + "@" + value);
        return new SearchPickerData.Selection(registry, SearchPickerData.SelectionMode.SELECTED, keys);
    }
}
