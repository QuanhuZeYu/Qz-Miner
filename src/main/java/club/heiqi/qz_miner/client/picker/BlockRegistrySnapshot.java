package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.block.Block;

import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.qz_miner.MyMod;

/**
 * 候选清单快照：<b>只读注册名清单 + Block 引用 + modId 前缀统计</b>，不做任何变体物化。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §1.7 D-9（{@code BlockVariantEnumerator.enumerate()}
 * 的替代者之一）；{@code team/P2-Miner-Provider-改造设计.md} §2.1/§M1。</p>
 *
 * <p><b>为什么独立于变体物化</b>：真正昂贵的是 {@code block.getSubBlocks(...)}（每方块一次多方块 API）、
 * 逐变体 {@code getDisplayName()} 与创造栏捕获；清单只需要 {@code Block.blockRegistry.getNameForObject}，
 * 因此清单快照的构建成本 = O(N) 次 O(1) 注册名查询 + 一次 modId 前缀统计，不含任何 ItemStack 分配。
 * 变体物化延后到首个真正需要该分片的窗口请求（见 {@link BlockVariantShardCache}）。</p>
 *
 * <p><b>顺序契约</b>（ADR §1.6(a)）：清单顺序 = {@code Block.blockRegistry} 的注册（插入）序，
 * 未排序遍历，保证浏览 lane 的「分片纯切片」语义与现状逐项一致。</p>
 *
 * <p><b>线程</b>：只在客户端主线程构建/读取（触碰 {@code Block.blockRegistry}）。</p>
 */
public final class BlockRegistrySnapshot {

    private static final int MAX_FAILURE_LOGS = 8;

    private final String[] registries;
    private final Block[] blocks;
    private final String[] modIds;
    private final Map<String, Integer> indexByRegistry;
    private final List<SearchPickerCategories.Category> modCategories;
    private final int modCategoryTotal;

    private BlockRegistrySnapshot(String[] registries, Block[] blocks, String[] modIds) {
        this.registries = registries;
        this.blocks = blocks;
        this.modIds = modIds;
        Map<String, Integer> index = new LinkedHashMap<String, Integer>();
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (int i = 0; i < registries.length; i++) {
            index.put(registries[i], Integer.valueOf(i));
            String modId = modIds[i];
            if (modId == null || modId.isEmpty()) {
                continue;
            }
            Integer current = counts.get(modId);
            counts.put(modId, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        this.indexByRegistry = Collections.unmodifiableMap(index);
        List<SearchPickerCategories.Category> categories = new ArrayList<SearchPickerCategories.Category>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            categories.add(new SearchPickerCategories.Category(entry.getKey(), entry.getKey(),
                    entry.getValue().intValue()));
        }
        this.modCategories = Collections.unmodifiableList(categories);
        this.modCategoryTotal = categories.size();
    }

    /**
     * 生产路径：一次遍历当前客户端 {@code Block.blockRegistry} 建立清单快照。
     *
     * <p>只做两件事：取注册名（失败限流告警后跳过该方块）+ 记录 Block 引用。注册名非法（缺冒号或
     * 冒号在首/末位）的条目被过滤，与旧 {@code enumerate()} 的过滤口径逐条一致。</p>
     *
     * @return 清单快照（非 null，可能为空清单）
     */
    public static BlockRegistrySnapshot capture() {
        List<String> registries = new ArrayList<String>();
        List<Block> blocks = new ArrayList<Block>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int failures = 0;
        for (Object value : Block.blockRegistry) {
            if (!(value instanceof Block)) {
                continue;
            }
            Block block = (Block) value;
            String registry = null;
            try {
                Object key = Block.blockRegistry.getNameForObject(block);
                registry = key == null ? null : key.toString();
            } catch (RuntimeException e) {
                if (failures++ < MAX_FAILURE_LOGS) {
                    MyMod.LOG.warn("Block picker registry-name lookup failed for {}", block, e);
                }
            } catch (LinkageError e) {
                if (failures++ < MAX_FAILURE_LOGS) {
                    MyMod.LOG.warn("Block picker registry-name lookup linkage failed for {}", block, e);
                }
            }
            if (!isValidRegistry(registry) || !seen.add(registry)) {
                continue;
            }
            registries.add(registry);
            blocks.add(block);
        }
        if (failures > MAX_FAILURE_LOGS) {
            MyMod.LOG.warn("Block picker suppressed {} additional registry-name lookup failures",
                    failures - MAX_FAILURE_LOGS);
        }
        return new BlockRegistrySnapshot(registries.toArray(new String[registries.size()]),
                blocks.toArray(new Block[blocks.size()]), modIdsOf(registries));
    }

    /**
     * 工具/测试入口：按给定顺序（{@link LinkedHashMap} 的迭代序）固化清单，不做注册表读取。
     *
     * @param orderedBlocks registry → Block；键必须非 null、非空，值必须非 null
     * @return 清单快照
     */
    public static BlockRegistrySnapshot of(Map<String, Block> orderedBlocks) {
        if (orderedBlocks == null) {
            throw new IllegalArgumentException("orderedBlocks must not be null");
        }
        List<String> registries = new ArrayList<String>(orderedBlocks.size());
        List<Block> blocks = new ArrayList<Block>(orderedBlocks.size());
        for (Map.Entry<String, Block> entry : orderedBlocks.entrySet()) {
            String registry = entry.getKey();
            if (registry == null || registry.isEmpty()) {
                throw new IllegalArgumentException("registry must not be empty");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("block must not be null: " + registry);
            }
            registries.add(registry);
            blocks.add(entry.getValue());
        }
        return new BlockRegistrySnapshot(registries.toArray(new String[registries.size()]),
                blocks.toArray(new Block[blocks.size()]), modIdsOf(registries));
    }

    /** @return 候选（清单）条目数 */
    public int size() {
        return registries.length;
    }

    /**
     * @param index 清单下标
     * @return 该下标的 registry 键
     */
    public String registry(int index) {
        return registries[index];
    }

    /**
     * @param index 清单下标
     * @return 该下标的 Block 引用
     */
    public Block block(int index) {
        return blocks[index];
    }

    /**
     * @param index 清单下标
     * @return 该下标的 modId（registry namespace）；无冒号时为 null
     */
    public String modId(int index) {
        return modIds[index];
    }

    /**
     * O(1) 定位（供 {@code exact} 使用）。
     *
     * @param registry registry 键
     * @return 清单下标；未命中返回 -1
     */
    public int indexOf(String registry) {
        if (registry == null) {
            return -1;
        }
        Integer index = indexByRegistry.get(registry);
        return index == null ? -1 : index.intValue();
    }

    /**
     * @param registry registry 键
     * @return Block 引用；未命中返回 null
     */
    public Block blockFor(String registry) {
        int index = indexOf(registry);
        return index < 0 ? null : blocks[index];
    }

    /**
     * @param registry registry 键
     * @return 是否在清单内
     */
    public boolean contains(String registry) {
        return indexOf(registry) >= 0;
    }

    /** @return 只读注册名清单（注册序） */
    public List<String> registries() {
        return Collections.unmodifiableList(Arrays.asList(registries));
    }

    /**
     * 分类快照（D-4）：清单级一次性 O(N) 前缀统计，modId 字典序，count = 该 mod 的候选数。
     *
     * @return 不可变分类列表
     */
    public List<SearchPickerCategories.Category> modCategories() {
        return modCategories;
    }

    /**
     * @param modId registry namespace
     * @return 该 mod 的候选数；未知返回 0
     */
    public int modCount(String modId) {
        if (modId == null || modId.isEmpty()) {
            return 0;
        }
        for (SearchPickerCategories.Category category : modCategories) {
            if (category.key().equals(modId)) {
                return category.count();
            }
        }
        return 0;
    }

    /** @return 分类条数（诊断用） */
    public int modCategoryCount() {
        return modCategoryTotal;
    }

    private static String[] modIdsOf(List<String> registries) {
        String[] modIds = new String[registries.size()];
        for (int i = 0; i < modIds.length; i++) {
            modIds[i] = BlockCandidate.modIdOf(registries.get(i));
        }
        return modIds;
    }

    /** 注册名合法性：必须含冒号且冒号不在首/末位（与旧枚举过滤同口径）。 */
    static boolean isValidRegistry(String registry) {
        if (registry == null || registry.isEmpty()) {
            return false;
        }
        int separator = registry.indexOf(':');
        return separator > 0 && separator < registry.length() - 1;
    }
}
