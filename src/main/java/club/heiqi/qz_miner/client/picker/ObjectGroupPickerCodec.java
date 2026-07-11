package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;

/** 完整 members 列表与方块 Picker 选择之间的无损转换。 */
public final class ObjectGroupPickerCodec implements Codec {
    /** 从当前 members 纯函数解码首个合法 selector。 */
    public SearchPickerData.Selection decode(Object value) {
        if (!(value instanceof List)) return null;
        for (Object raw : (List<?>) value) {
            if (!(raw instanceof String)) continue;
            try { return selection(ObjectGroupParser.parseSelector((String) raw)); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    /** 用本次选择替换同 registry selector；其它 raw 保持原位原样。 */
    public Object encode(Object currentValue, SearchPickerData.Selection selection) {
        if (!(currentValue instanceof List)) {
            throw new IllegalArgumentException("current value must be a list");
        }
        ObjectGroupSelector selected = selector(selection);
        String registry = selected.registry();
        int firstIndex = -1;
        List<Object> result = new ArrayList<Object>();
        for (Object raw : (List<?>) currentValue) {
            if (raw instanceof String) {
                try {
                    ObjectGroupSelector existing = ObjectGroupParser.parseSelector((String) raw);
                    if (existing.registry().equals(registry)) {
                        if (firstIndex < 0) firstIndex = result.size();
                        continue;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            result.add(raw);
        }
        String canonical = selected.canonical();
        if (firstIndex < 0) result.add(canonical); else result.add(firstIndex, canonical);
        return Collections.unmodifiableList(result);
    }

    private static SearchPickerData.Selection selection(ObjectGroupSelector selector) {
        if (selector.specificity() == ObjectGroupSelector.Specificity.WILDCARD) {
            return new SearchPickerData.Selection(selector.registry(), SearchPickerData.SelectionMode.ALL,
                    Collections.<String>emptyList());
        }
        List<String> keys = new ArrayList<String>();
        for (Integer meta : selector.metadata()) keys.add(selector.registry() + "@" + meta);
        SearchPickerData.SelectionMode mode = keys.size() == 1
                ? SearchPickerData.SelectionMode.SINGLE : SearchPickerData.SelectionMode.MULTIPLE;
        return new SearchPickerData.Selection(selector.registry(), mode, keys);
    }

    private static ObjectGroupSelector selector(SearchPickerData.Selection selection) {
        if (selection.mode() == SearchPickerData.SelectionMode.ALL) {
            return ObjectGroupSelector.wildcard(selection.candidateKey());
        }
        List<Integer> metas = new ArrayList<Integer>();
        for (String key : selection.variantKeys()) {
            ObjectGroupSelector parsed = ObjectGroupParser.parseSelector(key);
            if (!parsed.registry().equals(selection.candidateKey())
                    || parsed.specificity() != ObjectGroupSelector.Specificity.SINGLE) {
                throw new IllegalArgumentException("variant key does not belong to candidate");
            }
            metas.add(parsed.metadata().get(0));
        }
        return metas.size() == 1 ? ObjectGroupSelector.single(selection.candidateKey(), metas.get(0).intValue())
                : ObjectGroupSelector.set(selection.candidateKey(), metas);
    }
}
