package club.heiqi.qz_miner.client.picker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.ui.editor.SearchPickerData;

/** Picker Codec 的完整列表无损更新测试。 */
public class ObjectGroupPickerCodecTest {
    @Test
    public void decodesAllSingleAndMultiple() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        Assert.assertEquals(SearchPickerData.SelectionMode.ALL,
                codec.decode(Collections.singletonList("minecraft:log@*")).mode());
        Assert.assertEquals(SearchPickerData.SelectionMode.SINGLE,
                codec.decode(Collections.singletonList("minecraft:log@4")).mode());
        SearchPickerData.Selection multiple = codec.decode(Collections.singletonList("minecraft:log@[8,4]"));
        Assert.assertEquals(SearchPickerData.SelectionMode.MULTIPLE, multiple.mode());
        Assert.assertEquals(Arrays.asList("minecraft:log@4", "minecraft:log@8"), multiple.variantKeys());
    }

    @Test
    public void mergesRegistryAtFirstPositionAndPreservesRawValues() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        codec.decode(Arrays.asList("bad raw", "minecraft:log@0", "other:block@2", "minecraft:log@4"));
        Object encoded = codec.encode(new SearchPickerData.Selection("minecraft:log",
                SearchPickerData.SelectionMode.MULTIPLE, Arrays.asList("minecraft:log@8", "minecraft:log@12")));
        Assert.assertEquals(Arrays.asList("bad raw", "minecraft:log@[0,4,8,12]", "other:block@2"), encoded);
        Assert.assertEquals(encoded, codec.encode(new SearchPickerData.Selection("minecraft:log",
                SearchPickerData.SelectionMode.MULTIPLE, Arrays.asList("minecraft:log@8", "minecraft:log@12"))));
    }

    @Test
    public void wildcardCoversSpecificSelectors() {
        ObjectGroupPickerCodec codec = new ObjectGroupPickerCodec();
        codec.decode(Arrays.asList("minecraft:log@0", "minecraft:log@4", "raw"));
        List<?> encoded = (List<?>) codec.encode(new SearchPickerData.Selection("minecraft:log",
                SearchPickerData.SelectionMode.ALL, Collections.<String>emptyList()));
        Assert.assertEquals(Arrays.asList("minecraft:log@*", "raw"), encoded);
    }
}
