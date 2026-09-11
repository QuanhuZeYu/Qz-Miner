package club.heiqi.qz_miner.client.picker;

import java.util.Locale;

/**
 * 文本查询名字索引：归一化的本地化名 + 变体名拼接，<b>每个清单代际至多构建一次</b>。
 *
 * <p>契约出处：{@code team/P2-Miner-Provider-改造设计.md} §2.5 D-3（变体名拼串从构造期迁出）；
 * ADR §1.6(a)（同一 query + 同一版本下命中序必须稳定、确定）。</p>
 *
 * <p><b>为什么名字索引可以延后但不能省略</b>：rank 3（本地化名前缀）与 rank 5（本地化名/变体名包含）
 * 是既有搜索语义的一部分（GTNH 里多方块的变体名常是该方块唯一可搜的名字，例如矿石）。这些字符串只有
 * 物化分片才拿得到，因此索引构建 = 对清单做一次全量分片物化，只保留字符串、不保留候选对象。
 * 构建时机 = <b>首个文本查询</b>（不是注册期、不是面板打开、不是浏览 lane），
 * 因此「打开面板/滚动/点击」零成本；构建后同一清单代际内每次按键只做 O(N) 字符串 rank。</p>
 *
 * <p><b>确定性</b>：索引在首个文本查询时一次性建满，此后同一清单代际内不再变化 ⇒ 同一
 * {@code (query, revision)} 的命中序稳定（ADR A-02/A-03）；不存在「随缓存命中率变化的搜索结果」。</p>
 *
 * <p><b>有界</b>：规模 = O(N) 字符串（N = 清单条目数），随清单代际整体重建、随 {@code release()} 释放。</p>
 */
final class BlockPickerNameIndex {

    private final String[] localNames;
    private final String[] variantNames;

    private BlockPickerNameIndex(String[] localNames, String[] variantNames) {
        this.localNames = localNames;
        this.variantNames = variantNames;
    }

    /**
     * 构建名字索引（一次性全量物化清单）。
     *
     * @param snapshot 当前清单快照
     * @param shards   分片缓存（复用其物化结果；大清单下 LRU 可能淘汰部分条目，索引本身不依赖缓存留存）
     * @return 名字索引
     */
    static BlockPickerNameIndex build(BlockRegistrySnapshot snapshot, BlockVariantShardCache shards) {
        int size = snapshot.size();
        String[] localNames = new String[size];
        String[] variantNames = new String[size];
        for (int i = 0; i < size; i++) {
            String registry = snapshot.registry(i);
            BlockCandidate candidate = shards.candidateFor(registry, snapshot.block(i));
            localNames[i] = normalize(candidate.localizedName());
            StringBuilder names = new StringBuilder();
            for (BlockVariant variant : candidate.variants()) {
                names.append('\n').append(normalize(variant.name()));
            }
            variantNames[i] = names.toString();
        }
        return new BlockPickerNameIndex(localNames, variantNames);
    }

    /**
     * @param index 清单下标
     * @return 该条目的归一化本地化名（非 null）
     */
    String localName(int index) {
        return localNames[index];
    }

    /**
     * @param index 清单下标
     * @return 该条目的变体名归一化拼接（以 '\n' 分隔；无变体时为空串）
     */
    String variantNames(int index) {
        return variantNames[index];
    }

    /** @return 索引条目数 */
    int size() {
        return localNames.length;
    }

    /** 归一化口径与 {@code PickerQuery.normalize} 同源：trim + toLowerCase(Locale.ROOT)。 */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
