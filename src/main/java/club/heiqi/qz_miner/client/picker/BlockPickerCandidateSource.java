package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.config.ui.editor.PickerCandidateSource;
import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.PickerSourceVersion;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.field.PickerSourceGuard;

/**
 * 方块选择器惰性候选源：<b>单 registry 分片 + 惰性物化 + 脏标记惰性重建 + 有界缓存</b>。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §1.2/§1.3/§1.6/§2.3/§2.5；
 * {@code team/P2-Miner-Provider-改造设计.md} §2（M1/M2/M4/M5/M6/M7）。</p>
 *
 * <h3>结构</h3>
 * <ul>
 *   <li><b>清单快照</b>（{@link BlockRegistrySnapshot}）：O(N) 次注册名查询，零变体物化；</li>
 *   <li><b>分片缓存</b>（{@link BlockVariantShardCache}）：单 registry 粒度有界 LRU，窗口物化量 ∝ limit；</li>
 *   <li><b>搜索索引</b>（{@link BlockSearchIndex}）：清单级归一化 + rank，名字索引在首个文本查询时构建；</li>
 *   <li><b>命中序缓存</b>：LRU {@value #DEFAULT_HIT_ORDER_CAPACITY} 条（键 = {@link PickerQuery} 归一化值 +
 *       清单/名字代际），每条 int[] O(N)；浏览 lane 无分类过滤时零分配（恒等序）。</li>
 * </ul>
 *
 * <h3>失效（标脏 + 惰性重建，ADR §2.4）</h3>
 * <ul>
 *   <li><b>清单代际</b>：{@link #markRegistryDirty(String)} 只写一个 volatile 布尔（O(1)，事件回调内
 *       <b>不重建</b>）；重建发生在本源的下一次真实读取（{@code size/matchCount/page/exact/categories}），
 *       重建时 {@code registryRevision +1}、清分片与命中序；</li>
 *   <li><b>名字/图标代际</b>：唯一来源 = UILib 推送的 {@code onEnvironmentChanged(nameEpoch, resourceEpoch)}
 *       （Z-4：不推送则 {@code version()} 恒等，本源不轮询语言/资源状态、不自注册 reload listener）；
 *       首次推送是基线（不 +1），此后摄入值变化才 name/icon 各 +1；name 变化清分片与名字索引（标签重算），
 *       icon 变化只 +1（图标缓存归 UILib，按 {@code registryKey()} 重取）。</li>
 * </ul>
 *
 * <h3>线程（不可协商）</h3>
 * <p>SPI 全部入口经 {@link PickerSourceGuard#requireMainThread(String)} 断言客户端主线程（误用 fail-fast，
 * 不返回空数据）；{@link #markRegistryDirty(String)} 是唯一的非 SPI 入口，只写 volatile 布尔、可在任意
 * 线程调用（重建仍在主线程读取时发生）。</p>
 */
public final class BlockPickerCandidateSource implements PickerCandidateSource {

    /** 清单捕获策略（生产 = 真实 Block registry；测试注入假清单 ⇒ 零注册表读取）。 */
    public interface SnapshotSource {

        /**
         * @return 当前注册表的清单快照（非 null）
         */
        BlockRegistrySnapshot capture();
    }

    /** 命中序缓存条目上限（ADR §2.5：LRU 8 条，键 = 归一化 query + 代际）。 */
    public static final int DEFAULT_HIT_ORDER_CAPACITY = 8;

    private static volatile BlockPickerCandidateSource instance;

    private final SnapshotSource snapshots;
    private final BlockVariantShardCache shards;
    private final int hitOrderCapacity;
    private final Map<PickerQuery, int[]> hitOrders;

    private BlockRegistrySnapshot snapshot;
    private BlockSearchIndex searchIndex;
    private volatile boolean registryDirty = true;

    private long registryRevision;
    private long nameRevision;
    private long iconRevision;
    private boolean environmentIngested;
    private long ingestedNameEpoch;
    private long ingestedResourceEpoch;
    private PickerSourceVersion versionSnapshot = PickerSourceVersion.initial();

    private BlockPickerCandidateSource(SnapshotSource snapshots, BlockVariantShardCache shards, int hitOrderCapacity) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (shards == null) {
            throw new IllegalArgumentException("shards must not be null");
        }
        if (hitOrderCapacity < 1) {
            throw new IllegalArgumentException("hitOrderCapacity must be positive: " + hitOrderCapacity);
        }
        this.snapshots = snapshots;
        this.shards = shards;
        this.hitOrderCapacity = hitOrderCapacity;
        this.hitOrders = new LinkedHashMap<PickerQuery, int[]>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<PickerQuery, int[]> eldest) {
                return size() > BlockPickerCandidateSource.this.hitOrderCapacity;
            }
        };
    }

    /**
     * 可注入清单来源与分片缓存（测试用；生产走 {@link #getInstance()}）。
     *
     * @param snapshots 清单捕获策略
     * @param shards    分片缓存
     */
    public BlockPickerCandidateSource(SnapshotSource snapshots, BlockVariantShardCache shards) {
        this(snapshots, shards, DEFAULT_HIT_ORDER_CAPACITY);
    }

    /**
     * 进程级单例（客户端会话级；跨 screen 常驻，由 {@link #release()} 终止）。
     *
     * <p>创建本身不读注册表：清单在首次真实读取时才捕获（ADR §1.4「首次注册不再触发枚举」）。</p>
     *
     * @return 单例
     */
    public static BlockPickerCandidateSource getInstance() {
        BlockPickerCandidateSource current = instance;
        if (current == null) {
            synchronized (BlockPickerCandidateSource.class) {
                current = instance;
                if (current == null) {
                    current = new BlockPickerCandidateSource(new SnapshotSource() {
                        @Override
                        public BlockRegistrySnapshot capture() {
                            return BlockRegistrySnapshot.capture();
                        }
                    }, new BlockVariantShardCache());
                    instance = current;
                }
            }
        }
        return current;
    }

    // ==================== SPI：拉取面 ====================

    /** {@inheritDoc} 触发清单惰性重建（若已标脏）。 */
    @Override
    public int size() {
        PickerSourceGuard.requireMainThread("size");
        return ensureFresh().size();
    }

    /** {@inheritDoc} 纯计数读取，<b>不</b>触发重建（桥每帧读取必须零重建，ADR A-08）。 */
    @Override
    public long registryRevision() {
        PickerSourceGuard.requireMainThread("registryRevision");
        return registryRevision;
    }

    /** {@inheritDoc} 纯计数读取，不触发重建。 */
    @Override
    public long nameRevision() {
        PickerSourceGuard.requireMainThread("nameRevision");
        return nameRevision;
    }

    /** {@inheritDoc} 纯计数读取，不触发重建。 */
    @Override
    public long iconRevision() {
        PickerSourceGuard.requireMainThread("iconRevision");
        return iconRevision;
    }

    /**
     * {@inheritDoc} 自持版本快照：每帧读取零分配（默认实现会 new record），
     * 且只在计数器变化时更新 ⇒ 不推送则恒等（Z-4 判据）。
     */
    @Override
    public PickerSourceVersion version() {
        PickerSourceGuard.requireMainThread("version");
        return versionSnapshot;
    }

    /** {@inheritDoc} 浏览 lane 恒 = 清单长度；文本 lane = 真命中数（无 65 硬夹）。 */
    @Override
    public int matchCount(PickerQuery query) {
        PickerSourceGuard.requireMainThread("matchCount");
        requireQuery(query);
        BlockRegistrySnapshot current = ensureFresh();
        int[] order = orderFor(query);
        return order == null ? current.size() : order.length;
    }

    /** {@inheritDoc} 惰性分片：只物化窗口覆盖的 registry。 */
    @Override
    public List<SearchPickerData.Candidate> page(PickerQuery query, int offset, int limit) {
        PickerSourceGuard.requireMainThread("page");
        requireQuery(query);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        BlockRegistrySnapshot current = ensureFresh();
        if (limit == 0) {
            return Collections.emptyList();
        }
        int[] order = orderFor(query);
        int total = order == null ? current.size() : order.length;
        if (offset >= total) {
            return Collections.emptyList();
        }
        int last = (int) Math.min((long) total, (long) offset + (long) limit);
        List<SearchPickerData.Candidate> window =
                new ArrayList<SearchPickerData.Candidate>(last - offset);
        for (int i = offset; i < last; i++) {
            window.add(candidateAt(current, order == null ? i : order[i]));
        }
        return Collections.unmodifiableList(window);
    }

    /** {@inheritDoc} O(1) 定位 + 至多一次分片物化；不触发名字索引构建。 */
    @Override
    public SearchPickerData.Candidate exact(String candidateKey) {
        PickerSourceGuard.requireMainThread("exact");
        BlockCandidate candidate = blockCandidate(candidateKey);
        return candidate == null ? null : toCandidate(candidate);
    }

    /** {@inheritDoc} 维度 0 = 按 Mod（清单级前缀统计，随清单代际重建）。 */
    @Override
    public List<SearchPickerCategories.Category> categories(int dimension) {
        PickerSourceGuard.requireMainThread("categories");
        if (dimension < 0) {
            throw new IllegalArgumentException("dimension must not be negative: " + dimension);
        }
        if (dimension > 0) {
            return Collections.emptyList();
        }
        return ensureFresh().modCategories();
    }

    // ==================== SPI：下行面与生命周期 ====================

    /** {@inheritDoc} 环境代际唯一通道：摄入值变化才自增；基线推送不 +1。 */
    @Override
    public void onEnvironmentChanged(long nameEpoch, long resourceEpoch) {
        PickerSourceGuard.requireMainThread("onEnvironmentChanged");
        if (!environmentIngested) {
            environmentIngested = true;
            ingestedNameEpoch = nameEpoch;
            ingestedResourceEpoch = resourceEpoch;
            return;
        }
        boolean changed = false;
        if (nameEpoch != ingestedNameEpoch) {
            ingestedNameEpoch = nameEpoch;
            nameRevision++;
            // 标签代际：分片里的 localizedName/variant.name 与名字索引、命中序全部作废，按需重建
            shards.clear();
            hitOrders.clear();
            if (searchIndex != null) {
                searchIndex.invalidateNames();
            }
            changed = true;
        }
        if (resourceEpoch != ingestedResourceEpoch) {
            ingestedResourceEpoch = resourceEpoch;
            iconRevision++;
            changed = true;
        }
        if (changed) {
            refreshVersionSnapshot();
        }
    }

    /** {@inheritDoc} 释放清单/分片/命中序缓存；版本号不回绕，下次读取按首次建快照语义重建。 */
    @Override
    public void release() {
        PickerSourceGuard.requireMainThread("release");
        snapshot = null;
        searchIndex = null;
        shards.clear();
        hitOrders.clear();
        registryDirty = true;
    }

    // ==================== Miner 侧入口 ====================

    /**
     * 注册表变更标脏（O(1)，<b>不重建</b>）。
     *
     * <p>调用点：{@code FMLLoadCompleteEvent}（首次建快照）、{@code FMLModIdMappingEvent}（重映射）、
     * 客户端 tick 节流兜底（{@code Block.blockRegistry.getKeys().size()} 变化）。可在任意线程调用：
     * 只写 volatile 布尔，重建仍只发生在主线程的下一次真实读取（ADR §2.4「标脏 + 首窗重建」）。</p>
     *
     * @param reason 失效缘由（诊断用）
     */
    public void markRegistryDirty(String reason) {
        registryDirty = true;
    }

    /**
     * 候选域方块数据（供图标源取 ItemStack）：O(1) 定位 + 至多一次分片物化。
     *
     * @param candidateKey 候选键
     * @return 候选域数据；未命中返回 null
     */
    BlockCandidate blockCandidate(String candidateKey) {
        PickerSourceGuard.requireMainThread("blockCandidate");
        if (candidateKey == null || candidateKey.isEmpty()) {
            return null;
        }
        BlockRegistrySnapshot current = ensureFresh();
        int index = current.indexOf(candidateKey);
        return index < 0 ? null : shards.candidateFor(current.registry(index), current.block(index));
    }

    /**
     * 候选域分类：只有清单内已知 key 有分类（未知/非法 key 返回 null，与旧 byRegistry 语义一致）。
     *
     * @param candidateKey 候选键
     * @return modId；未知返回 null
     */
    public String categoryOf(String candidateKey) {
        PickerSourceGuard.requireMainThread("categoryOf");
        if (candidateKey == null) {
            return null;
        }
        BlockRegistrySnapshot current = ensureFresh();
        int index = current.indexOf(candidateKey);
        return index < 0 ? null : current.modId(index);
    }

    /** @return 是否处于「已标脏、待重建」状态（诊断/测试探针） */
    public boolean isRegistryDirty() {
        return registryDirty;
    }

    /** @return 分片缓存条目数（诊断/测试探针） */
    public int shardCacheSize() {
        return shards.size();
    }

    // ==================== 内部 ====================

    /** 惰性重建清单（含索引/分片/命中序失效）：只在真实读取入口调用。 */
    private BlockRegistrySnapshot ensureFresh() {
        PickerSourceGuard.requireMainThread("ensureFresh");
        if (!registryDirty && snapshot != null) {
            return snapshot;
        }
        BlockRegistrySnapshot captured = snapshots.capture();
        snapshot = captured == null ? BlockRegistrySnapshot.of(new LinkedHashMap<String, net.minecraft.block.Block>())
                : captured;
        shards.clear();
        hitOrders.clear();
        searchIndex = new BlockSearchIndex(snapshot, shards);
        registryDirty = false;
        registryRevision++;
        refreshVersionSnapshot();
        return snapshot;
    }

    /** 命中序（含 LRU 缓存）；{@code null} = 清单恒等序（不缓存、零分配）。 */
    private int[] orderFor(PickerQuery query) {
        int[] cached = hitOrders.get(query);
        if (cached != null) {
            return cached;
        }
        int[] computed = searchIndex.orderFor(query);
        if (computed != null) {
            hitOrders.put(query, computed);
        }
        return computed;
    }

    private SearchPickerData.Candidate candidateAt(BlockRegistrySnapshot current, int index) {
        BlockCandidate candidate = shards.candidateFor(current.registry(index), current.block(index));
        return toCandidate(candidate);
    }

    private void refreshVersionSnapshot() {
        versionSnapshot = new PickerSourceVersion(registryRevision, nameRevision, iconRevision);
    }

    private static void requireQuery(PickerQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
    }

    /** 候选域数据 → 面板数据（变体键 = {@code registry@meta}，与 canonical/wire 语义无关，纯投影）。 */
    static SearchPickerData.Candidate toCandidate(BlockCandidate candidate) {
        List<SearchPickerData.Variant> variants =
                new ArrayList<SearchPickerData.Variant>(candidate.variants().size());
        for (BlockVariant variant : candidate.variants()) {
            String key = candidate.registry() + "@" + variant.metadata();
            variants.add(new SearchPickerData.Variant(key, variant.name() + " (" + variant.metadata() + ")"));
        }
        return new SearchPickerData.Candidate(candidate.registry(), candidate.localizedName(), variants);
    }
}
