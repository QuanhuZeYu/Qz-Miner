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
    private List<String> currentMembers = Collections.emptyList();

    /** 记录完整只读 members 快照，并解码首个合法 selector。 */
    public SearchPickerData.Selection decode(Object value) {
        if (!(value instanceof List)) return null;
        List<String> copy = new ArrayList<String>();
        SearchPickerData.Selection first = null;
        for (Object raw : (List<?>) value) {
            if (!(raw instanceof String)) return null;
            String member = (String) raw;
            copy.add(member);
            if (first == null) {
                try { first = selection(ObjectGroupParser.parseSelector(member)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        currentMembers = Collections.unmodifiableList(copy);
        return first;
    }

    /** 将选择合并回当前完整 members；无关或畸形 raw 保持原位原样。 */
    public Object encode(SearchPickerData.Selection selection) {
        ObjectGroupSelector selected = selector(selection);
        String registry = selected.registry();
        int mask = selected.metadataMask();
        int firstIndex = -1;
        List<String> result = new ArrayList<String>();
        for (String raw : currentMembers) {
            try {
                ObjectGroupSelector existing = ObjectGroupParser.parseSelector(raw);
                if (existing.registry().equals(registry)) {
                    if (firstIndex < 0) firstIndex = result.size();
                    mask |= existing.metadataMask();
                    continue;
                }
            } catch (IllegalArgumentException ignored) { }
            result.add(raw);
        }
        String canonical = ObjectGroupSelector.fromMask(registry, mask).canonical();
        if (firstIndex < 0) result.add(canonical); else result.add(firstIndex, canonical);
        currentMembers = Collections.unmodifiableList(new ArrayList<String>(result));
        return currentMembers;
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
