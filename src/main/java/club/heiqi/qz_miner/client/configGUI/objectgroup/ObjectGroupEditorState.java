package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.ValueSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.StructuredListModel;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;

/**
 * 对象组编辑器的状态与编辑事务（M6）。
 *
 * <p><b>唯一真值</b>：配置草稿 {@code client.objectGroups}。本类不保存第二份配置真值——
 * 所有读都从 {@link #rows()}（草稿的 keyed 投影）派生，所有写都经
 * {@link DraftSignalAdapter#onFieldEdit} 回到同一草稿事务。</p>
 *
 * <p><b>校验真值</b>：冲突与结构错误只来自 {@link ObjectGroupParser#parse(Object)} 的
 * {@code errors()}（唯一真源）；本地守卫只用于「写入前拒绝明显非法的新值」，
 * 不复制 {@code findOverlapErrors} 等解析器内部逻辑。</p>
 *
 * <p><b>无 UI 依赖</b>：本类不引用任何 SceneNode / 控件 / 主题，纯信号 + 纯计算，可 headless 直测。
 * 响应式只暴露 {@link Signal}（不创建 Effect/Computed），派生查询（视图/摘要/校验）使用
 * 「引用变化即失效」的有界缓存（规模上限 64 组 / 2048 成员，重算成本可控）。</p>
 *
 * <p><b>编辑事务口径</b>：{@code onFieldEdit} 是 void 且无拒绝分支，因此每个命令都先在本地
 * 守卫通过后才写入；被拒绝的命令不改变 {@link #rows()} 与草稿。</p>
 */
public final class ObjectGroupEditorState {

    /** 字段 path（与 schema 一致）。 */
    public static final String PATH = "client.objectGroups";
    /** 组成员：组 id。 */
    public static final String MEMBER_ID = "id";
    /** 组成员：模式 id 列表。 */
    public static final String MEMBER_MODES = "modes";
    /** 组成员：成员 selector 列表。 */
    public static final String MEMBER_MEMBERS = "members";

    /** 行级错误 path 前缀（{@code client.objectGroups[i]}）。 */
    private static final Pattern ROW_PATH = Pattern.compile("^client\\.objectGroups\\[(\\d+)\\]");

    /** 列表谓词（固定集合，不做列排序——顺序无语义但必须保真）。 */
    public enum Filter {
        /** 全部组。 */
        ALL,
        /** 与其它组存在模式+选择器重叠的组。 */
        CONFLICT,
        /** 未选择任何已知模式、因而永不生效的组。 */
        INACTIVE,
        /** 含通配选择器（{@code registry@*}）的组。 */
        WILDCARD
    }

    /** 行状态位。 */
    public enum Flag {
        /** 与其它组模式+选择器重叠（校验真源：解析器 errors）。 */
        CONFLICT,
        /** 未选择模式 ⇒ 永不匹配。 */
        INACTIVE,
        /**
         * 行级结构错误（id 重复/为空/超长、成员为空、selector 非法等）。
         *
         * <p>与 {@link #CONFLICT} 分开：结构错误落行可定位，但**不计入冲突组数、也不进 [冲突] 谓词**
         * （[冲突] 与摘要的冲突计数只表示「模式+选择器重叠」这一件事）。</p>
         */
        ERROR,
        /** 组成员为空（新建组尚未完成，配置尚不可保存）。 */
        INCOMPLETE,
        /** 含通配选择器。 */
        WILDCARD
    }

    /** 编辑命令的结构化拒绝原因（由 UI 映射 i18n 文案，不在本类拼文案）。 */
    public enum Rejection {
        /** 未被拒绝。 */
        NONE,
        /** 目标组不存在（行已消失）。 */
        NO_TARGET,
        /** 组 id 为空。 */
        ID_EMPTY,
        /** 组 id 超过 {@link ObjectGroup#MAX_ID_LENGTH}。 */
        ID_TOO_LONG,
        /** 组 id 与其它组重复。 */
        ID_DUPLICATED,
        /** 组数已达 {@link ObjectGroupRuleSet#MAX_GROUPS}。 */
        GROUP_LIMIT,
        /** 成员数已达 {@link ObjectGroup#MAX_MEMBERS}。 */
        MEMBER_LIMIT,
        /** 成员总数已达 {@link ObjectGroupRuleSet#MAX_TOTAL_MEMBERS}。 */
        TOTAL_LIMIT,
        /** mode id 不在 {@link ObjectGroupMode#ids()}。 */
        MODE_UNKNOWN,
        /** selector 不可解析为 registry@meta。 */
        SELECTOR_UNPARSABLE,
        /** selector 超过 {@link ObjectGroupSelector#MAX_CANONICAL_LENGTH}。 */
        SELECTOR_TOO_LONG,
        /** 没有可撤销的删除。 */
        NOTHING_TO_UNDO,
        /** 成员下标越界。 */
        INDEX_OUT_OF_RANGE
    }

    /** 单行只读视图（每帧快照，不可变）。 */
    public static final class RowView {
        private final long key;
        private final int index;
        private final String id;
        private final List<String> modes;
        private final List<String> unknownModes;
        private final List<String> members;
        private final Set<Flag> flags;
        private final String error;
        private final boolean selected;

        RowView(long key, int index, String id, List<String> modes, List<String> unknownModes,
                List<String> members, Set<Flag> flags, String error, boolean selected) {
            this.key = key;
            this.index = index;
            this.id = id;
            this.modes = Collections.unmodifiableList(modes);
            this.unknownModes = Collections.unmodifiableList(unknownModes);
            this.members = Collections.unmodifiableList(members);
            this.flags = Collections.unmodifiableSet(flags);
            this.error = error;
            this.selected = selected;
        }

        /** @return 稳定行 key（用于 keyed 渲染与选中） */
        public long key() { return key; }
        /** @return 组在配置中的位次（0 起；顺序无语义但必须保真） */
        public int index() { return index; }
        /** @return 组 id（可能为空串，表示未完成） */
        public String id() { return id; }
        /** @return 已知模式 id（保序） */
        public List<String> modes() { return modes; }
        /** @return 不在 {@link ObjectGroupMode#ids()} 中的历史值（保序、无损、可取消） */
        public List<String> unknownModes() { return unknownModes; }
        /** @return 成员 selector 字符串视图（无损口径，不应被丢弃） */
        public List<String> members() { return members; }
        /** @return 状态位集合 */
        public Set<Flag> flags() { return flags; }
        /** @return 该行的解析器错误信息，无错时为 null */
        public String error() { return error; }
        /** @return 是否为当前选中行 */
        public boolean selected() { return selected; }

        /**
         * 判定状态位。
         *
         * @param flag 状态位
         * @return 是否具备该状态
         */
        public boolean hasFlag(Flag flag) { return flags.contains(flag); }

        /**
         * 判定是否匹配固定谓词。
         *
         * @param filter 谓词
         * @return 是否命中
         */
        public boolean matches(Filter filter) {
            switch (filter) {
                case CONFLICT: return flags.contains(Flag.CONFLICT);
                case INACTIVE: return flags.contains(Flag.INACTIVE);
                case WILDCARD: return flags.contains(Flag.WILDCARD);
                default: return true;
            }
        }

        /**
         * 判定是否命中搜索词（id / 成员 / 模式，大小写不敏感）。
         *
         * @param query 搜索词（空白视为无搜索）
         * @return 是否命中
         */
        public boolean matchesQuery(String query) {
            if (query == null) return true;
            String needle = query.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return true;
            if (id.toLowerCase(Locale.ROOT).contains(needle)) return true;
            for (String member : members) if (member.toLowerCase(Locale.ROOT).contains(needle)) return true;
            for (String mode : modes) if (mode.toLowerCase(Locale.ROOT).contains(needle)) return true;
            return false;
        }
    }

    /** 摘要四元组（配置页摘要卡与编辑视图顶部条共用）。 */
    public static final class Summary {
        private final int groupCount;
        private final int memberCount;
        private final int conflictCount;
        private final int inactiveCount;
        private final int incompleteCount;

        Summary(int groupCount, int memberCount, int conflictCount, int inactiveCount, int incompleteCount) {
            this.groupCount = groupCount;
            this.memberCount = memberCount;
            this.conflictCount = conflictCount;
            this.inactiveCount = inactiveCount;
            this.incompleteCount = incompleteCount;
        }

        /** @return 组数 */
        public int groupCount() { return groupCount; }
        /** @return 成员总数 */
        public int memberCount() { return memberCount; }
        /** @return 冲突组数（解析器真源） */
        public int conflictCount() { return conflictCount; }
        /** @return 未生效组数 */
        public int inactiveCount() { return inactiveCount; }
        /** @return 未完成组数（成员为空或 id 为空） */
        public int incompleteCount() { return incompleteCount; }

        /** @return 是否无组（双空状态之一） */
        public boolean isEmpty() { return groupCount == 0; }
    }

    /** 编辑命令结果。 */
    public static final class EditResult {
        private static final EditResult ACCEPTED = new EditResult(Rejection.NONE);

        private final Rejection rejection;

        private EditResult(Rejection rejection) { this.rejection = rejection; }

        static EditResult ok() { return ACCEPTED; }

        static EditResult rejected(Rejection rejection) { return new EditResult(rejection); }

        /** @return 是否被接受并已写入草稿 */
        public boolean accepted() { return rejection == Rejection.NONE; }
        /** @return 拒绝原因（accepted 时为 {@link Rejection#NONE}） */
        public Rejection rejection() { return rejection; }
    }

    /** 行级解析错误：消息 + 是否属于「模式/重叠」类（决定是否打 {@link Flag#CONFLICT} 标记）。 */
    private static final class RowError {
        private final String message;
        private final boolean conflict;

        RowError(String message, boolean conflict) {
            this.message = message;
            this.conflict = conflict;
        }
    }

    /** 最近一次删除（撤销入口的唯一状态）。 */
    private static final class RemovedGroup {
        private final int index;
        private final Map<String, Object> value;
        private final String id;

        RemovedGroup(int index, Map<String, Object> value, String id) {
            this.index = index;
            this.value = value;
            this.id = id;
        }
    }

    private final String path;
    private final DraftSignalAdapter adapter;
    private final ValueSpec objectSpec;
    private final ReadableSignal<Object> draftSignal;
    private final Signal<List<StructuredListModel.Row>> rows;
    private final StructuredListModel.IdentityLineage lineage;
    private final Signal<Long> selectedKey = Signal.create(Long.valueOf(-1L));
    private final Signal<String> search = Signal.create("");
    private final Signal<Filter> filter = Signal.create(Filter.ALL);
    private final Signal<Boolean> canUndoRemove = Signal.create(Boolean.FALSE);
    /** 最近一次删除的原始值（撤销恢复用；不以 Signal 值做逻辑判断，避免 flush 时序耦合）。 */
    private RemovedGroup removedValue;

    /** 校验缓存：rows 引用变化即失效（有界：单份）。 */
    private List<StructuredListModel.Row> parseCacheRows;
    private ObjectGroupParser.ParseResult parseCache;
    /** 视图缓存：rows / 选中 / 校验 任一变化即失效。 */
    private List<RowView> viewCache;
    private List<StructuredListModel.Row> viewCacheRows;
    private long viewCacheSelected = Long.MIN_VALUE;
    private ObjectGroupParser.ParseResult viewCacheParse;
    /** 过滤视图缓存：视图 / 搜索 / 谓词 任一变化即失效。 */
    private List<RowView> visibleCache;
    private List<RowView> visibleCacheSource;
    private String visibleCacheQuery;
    private Filter visibleCacheFilter;
    /** 摘要缓存：视图引用变化即失效。 */
    private Summary summaryCache;
    private List<RowView> summaryCacheSource;

    /**
     * 创建状态（不注册任何 runtime 绑定；由 {@link ObjectGroupEditorFieldRenderer} 负责
     * {@code rt.bind(state.draftSignal(), state::syncExternal)}）。
     *
     * @param spec    字段元数据（须为结构化列表）
     * @param adapter 草稿适配器
     */
    public ObjectGroupEditorState(FieldSpec spec, DraftSignalAdapter adapter) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        if (adapter == null) throw new IllegalArgumentException("adapter must not be null");
        this.path = spec.path();
        this.adapter = adapter;
        ValueSpec listSpec = spec.valueSpec();
        this.objectSpec = listSpec == null ? null : listSpec.element();
        this.draftSignal = adapter.draftSignal(path);
        List<StructuredListModel.Row> initial = StructuredListModel.fromValue(draftSignal.get());
        this.rows = Signal.create(initial);
        this.lineage = new StructuredListModel.IdentityLineage(
                objectSpec == null ? null : objectSpec.identityMember());
        this.lineage.observe(initial);
    }

    // ------------------------------------------------------------------ 只读面

    /** @return 字段 path */
    public String path() { return path; }

    /** @return 草稿信号（编辑视图外部变化同步入口） */
    public ReadableSignal<Object> draftSignal() { return draftSignal; }

    /** @return 草稿的 keyed 行投影（唯一读面，不缓存第二份真值） */
    public ReadableSignal<List<StructuredListModel.Row>> rows() { return rows; }

    /** @return 选中行 key（-1 表示未选中） */
    public Signal<Long> selectedKey() { return selectedKey; }

    /** @return 搜索词信号 */
    public Signal<String> search() { return search; }

    /** @return 谓词信号 */
    public Signal<Filter> filter() { return filter; }

    /** @return 是否可撤销最近一次删除 */
    public Signal<Boolean> canUndoRemove() {
        return canUndoRemove;
    }

    /**
     * 草稿外部变化同步（配置页 reload / 撤销 / 保存后回同步）。
     *
     * @param value 新草稿值
     */
    public void syncExternal(Object value) {
        if (StructuredListModel.valuesEqual(rows.get(), value)) return;
        rows.set(StructuredListModel.sync(rows.get(), value, objectSpec, lineage));
        if (selectedKey.get().longValue() != -1L && selection() == null) {
            selectedKey.set(Long.valueOf(-1L));
        }
    }

    /**
     * 重置为当前草稿快照（关闭视图再打开时调用；不丢草稿、不重建第二份真值）。
     */
    public void refreshFromDraft() {
        parseCacheRows = null;
        syncExternal(draftSignal.get());
    }

    /** @return 解析器结果（唯一校验真源） */
    public ObjectGroupParser.ParseResult parseResult() {
        List<StructuredListModel.Row> current = rows.get();
        if (parseCache == null || current != parseCacheRows) {
            parseCacheRows = current;
            parseCache = ObjectGroupParser.parse(StructuredListModel.toValue(current));
        }
        return parseCache;
    }

    /** @return 全部行视图（保序） */
    public List<RowView> views() {
        List<StructuredListModel.Row> current = rows.get();
        long selected = selectedKey.get().longValue();
        ObjectGroupParser.ParseResult parse = parseResult();
        if (viewCache == null || current != viewCacheRows || selected != viewCacheSelected || parse != viewCacheParse) {
            viewCache = buildViews(current, parse, selected);
            viewCacheRows = current;
            viewCacheSelected = selected;
            viewCacheParse = parse;
        }
        return viewCache;
    }

    /** @return 命中搜索与谓词的行视图（保序） */
    public List<RowView> visibleViews() {
        List<RowView> source = views();
        String query = search.get();
        Filter current = filter.get();
        if (visibleCache == null || source != visibleCacheSource || current != visibleCacheFilter
                || (query == null ? visibleCacheQuery != null : !query.equals(visibleCacheQuery))) {
            List<RowView> out = new ArrayList<RowView>();
            for (RowView view : source) {
                if (view.matches(current) && view.matchesQuery(query)) out.add(view);
            }
            visibleCache = out;
            visibleCacheSource = source;
            visibleCacheQuery = query;
            visibleCacheFilter = current;
        }
        return visibleCache;
    }

    /** @return 摘要四元组 */
    public Summary summary() {
        List<RowView> current = views();
        if (summaryCache == null || current != summaryCacheSource) {
            int members = 0;
            int conflicts = 0;
            int inactive = 0;
            int incomplete = 0;
            for (RowView view : current) {
                members += view.members().size();
                if (view.hasFlag(Flag.CONFLICT)) conflicts++;
                if (view.hasFlag(Flag.INACTIVE)) inactive++;
                if (view.hasFlag(Flag.INCOMPLETE)) incomplete++;
            }
            summaryCache = new Summary(current.size(), members, conflicts, inactive, incomplete);
            summaryCacheSource = current;
        }
        return summaryCache;
    }

    /** @return 当前选中行视图，未选中时为 null */
    public RowView selection() {
        long key = selectedKey.get().longValue();
        if (key == -1L) return null;
        for (RowView view : views()) if (view.key() == key) return view;
        return null;
    }

    /**
     * 按 key 取行视图。
     *
     * @param key 行 key
     * @return 行视图，不存在时 null
     */
    public RowView viewOf(long key) {
        for (RowView view : views()) if (view.key() == key) return view;
        return null;
    }

    /**
     * 按组 id 取行视图。
     *
     * @param id 组 id
     * @return 行视图，不存在时 null
     */
    public RowView viewOfId(String id) {
        if (id == null) return null;
        for (RowView view : views()) if (id.equals(view.id())) return view;
        return null;
    }

    /**
     * 选中一行。
     *
     * @param key 行 key；-1 表示清空选择
     */
    public void select(long key) {
        selectedKey.set(Long.valueOf(key));
    }

    /** 选中首行（宽挡打开且未选中时的默认）；无行时清空选择。 */
    public void selectFirst() {
        List<RowView> current = views();
        select(current.isEmpty() ? -1L : current.get(0).key());
    }

    // ------------------------------------------------------------------ 编辑事务

    /**
     * 新增组：{@code group_<n>} + 空模式 + 空成员（未完成状态，会阻断保存，由 UI 显式告知）。
     *
     * @return 命令结果
     */
    public EditResult addGroup() {
        List<Map<String, Object>> groups = groups();
        if (groups.size() >= ObjectGroupRuleSet.MAX_GROUPS) return EditResult.rejected(Rejection.GROUP_LIMIT);
        String id = nextFreeId(groups);
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put(MEMBER_ID, id);
        group.put(MEMBER_MODES, new ArrayList<Object>());
        group.put(MEMBER_MEMBERS, new ArrayList<Object>());
        groups.add(group);
        clearRemoved();
        write(groups);
        selectById(id);
        return EditResult.ok();
    }

    /**
     * 删除组（保留最近一次删除以便撤销）。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult removeGroup(long key) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        Map<String, Object> removedValue = copyGroup(groups.get(index));
        String removedId = stringValue(removedValue.get(MEMBER_ID));
        groups.remove(index);
        clearRemoved();
        write(groups);
        this.removedValue = new RemovedGroup(index, removedValue, removedId);
        canUndoRemove.set(Boolean.TRUE);
        if (selectedKey.get().longValue() == key) {
            List<RowView> current = views();
            if (current.isEmpty()) select(-1L);
            else select(current.get(Math.min(index, current.size() - 1)).key());
        }
        return EditResult.ok();
    }

    /**
     * 撤销最近一次删除（恢复原位次与内容）；任一其它结构性编辑后该入口失效。
     *
     * @return 命令结果
     */
    public EditResult undoRemove() {
        RemovedGroup pending = removedValue;
        if (pending == null) return EditResult.rejected(Rejection.NOTHING_TO_UNDO);
        List<Map<String, Object>> groups = groups();
        if (groups.size() >= ObjectGroupRuleSet.MAX_GROUPS) return EditResult.rejected(Rejection.GROUP_LIMIT);
        int at = Math.min(pending.index, groups.size());
        groups.add(at, copyGroup(pending.value));
        clearRemoved();
        write(groups);
        selectById(pending.id);
        return EditResult.ok();
    }

    /**
     * 改组 id（就地校验；重复/空/超长在写入前拒绝）。
     *
     * @param key   行 key
     * @param newId 新 id
     * @return 命令结果
     */
    public EditResult renameGroup(long key, String newId) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        String id = newId == null ? "" : newId;
        if (id.isEmpty()) return EditResult.rejected(Rejection.ID_EMPTY);
        if (id.length() > ObjectGroup.MAX_ID_LENGTH) return EditResult.rejected(Rejection.ID_TOO_LONG);
        for (int i = 0; i < groups.size(); i++) {
            if (i != index && id.equals(stringValue(groups.get(i).get(MEMBER_ID)))) {
                return EditResult.rejected(Rejection.ID_DUPLICATED);
            }
        }
        groups.get(index).put(MEMBER_ID, id);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 设置组的已知模式集合（未知历史值自动保留，不被丢弃）。
     *
     * @param key   行 key
     * @param modes 已知模式 id 集合
     * @return 命令结果
     */
    public EditResult setGroupModes(long key, List<String> modes) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        Set<String> known = knownModeIds();
        List<Object> next = new ArrayList<Object>();
        List<Object> unknown = unknownModesOf(groups.get(index));
        if (modes != null) {
            for (String mode : modes) {
                if (mode == null || !known.contains(mode)) return EditResult.rejected(Rejection.MODE_UNKNOWN);
                if (!next.contains(mode)) next.add(mode);
            }
        }
        next.addAll(unknown);
        groups.get(index).put(MEMBER_MODES, next);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 切换单个模式的多选状态。
     *
     * @param key    行 key
     * @param modeId 模式 id
     * @return 命令结果
     */
    public EditResult toggleGroupMode(long key, String modeId) {
        RowView view = viewOf(key);
        if (view == null) return EditResult.rejected(Rejection.NO_TARGET);
        List<String> modes = new ArrayList<String>(view.modes());
        if (modes.contains(modeId)) modes.remove(modeId);
        else modes.add(modeId);
        return setGroupModes(key, modes);
    }

    /**
     * 删除一个历史失效模式值（可取消的失效项）。
     *
     * @param key    行 key
     * @param modeId 失效 mode 原值
     * @return 命令结果
     */
    public EditResult removeUnknownMode(long key, String modeId) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        // 只保留「已知模式」再拼回「未取消的失效项」：先前实现把 raw 整体（含其它失效项）拼上 next，
        // 会让其余失效值在草稿里二次追加（复核 D1）。
        Set<String> ids = knownModeIds();
        List<Object> modes = new ArrayList<Object>();
        Object raw = groups.get(index).get(MEMBER_MODES);
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (ids.contains(stringValue(item))) modes.add(item);
            }
        }
        List<Object> unknown = unknownModesOf(groups.get(index));
        unknown.remove(modeId);
        modes.addAll(unknown);
        groups.get(index).put(MEMBER_MODES, modes);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 组上移一位（显式移动命令，替代拖拽排序）。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult moveGroupUp(long key) { return moveGroup(key, -1); }

    /**
     * 置顶。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult moveGroupTop(long key) { return moveGroupTo(key, 0); }

    /**
     * 组下移一位。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult moveGroupDown(long key) { return moveGroup(key, 1); }

    /**
     * 置底。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult moveGroupBottom(long key) { return moveGroupTo(key, Integer.MAX_VALUE); }

    /**
     * 复制组：当前组整体复制并插入其后，id 自动取未占用的 {@code <id>_copy[_n]}（配置数据，不本地化）。
     *
     * @param key 行 key
     * @return 命令结果
     */
    public EditResult duplicateGroup(long key) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        if (groups.size() >= ObjectGroupRuleSet.MAX_GROUPS) return EditResult.rejected(Rejection.GROUP_LIMIT);
        int copiedMembers = mutableList(groups.get(index).get(MEMBER_MEMBERS)).size();
        if (totalMembers(groups) + copiedMembers > ObjectGroupRuleSet.MAX_TOTAL_MEMBERS) {
            return EditResult.rejected(Rejection.TOTAL_LIMIT);
        }
        Map<String, Object> copy = copyGroup(groups.get(index));
        String newId = nextFreeCopyId(groups, stringValue(copy.get(MEMBER_ID)));
        copy.put(MEMBER_ID, newId);
        groups.add(index + 1, copy);
        clearRemoved();
        write(groups);
        selectById(newId);
        return EditResult.ok();
    }

    /**
     * 追加成员 selector。
     *
     * @param key      行 key
     * @param selector 规范化 selector（{@code registry@meta}）
     * @return 命令结果
     */
    public EditResult addMember(long key, String selector) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        Rejection invalid = validateSelector(selector);
        if (invalid != Rejection.NONE) return EditResult.rejected(invalid);
        List<Object> members = mutableList(groups.get(index).get(MEMBER_MEMBERS));
        if (members.size() >= ObjectGroup.MAX_MEMBERS) return EditResult.rejected(Rejection.MEMBER_LIMIT);
        if (totalMembers(groups) >= ObjectGroupRuleSet.MAX_TOTAL_MEMBERS) {
            return EditResult.rejected(Rejection.TOTAL_LIMIT);
        }
        members.add(selector);
        groups.get(index).put(MEMBER_MEMBERS, members);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 在指定位置插入成员（原位恢复用；追加请用 {@link #addMember(long, String)}）。
     *
     * <p>存在意义：picker 面板内的删除需要「按原位次撤销」，追加语义会改变成员顺序，因此插入
     * 是独立的编辑事务而不是 addMember 的变体。</p>
     *
     * @param key      行 key
     * @param index    插入下标（越界自动 clamp 到列表长度）
     * @param selector 规范化 selector
     * @return 命令结果
     */
    public EditResult insertMember(long key, int index, String selector) {
        List<Map<String, Object>> groups = groups();
        int row = indexOfKey(groups, key);
        if (row < 0) return EditResult.rejected(Rejection.NO_TARGET);
        Rejection invalid = validateSelector(selector);
        if (invalid != Rejection.NONE) return EditResult.rejected(invalid);
        List<Object> members = mutableList(groups.get(row).get(MEMBER_MEMBERS));
        if (members.size() >= ObjectGroup.MAX_MEMBERS) return EditResult.rejected(Rejection.MEMBER_LIMIT);
        if (totalMembers(groups) >= ObjectGroupRuleSet.MAX_TOTAL_MEMBERS) {
            return EditResult.rejected(Rejection.TOTAL_LIMIT);
        }
        int at = Math.max(0, Math.min(index, members.size()));
        members.add(at, selector);
        groups.get(row).put(MEMBER_MEMBERS, members);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 删除成员（允许删到空 ⇒ 该组转为「未完成」，由摘要与行状态显式提示）。
     *
     * @param key   行 key
     * @param index 成员下标
     * @return 命令结果
     */
    public EditResult removeMember(long key, int index) {
        List<Map<String, Object>> groups = groups();
        int row = indexOfKey(groups, key);
        if (row < 0) return EditResult.rejected(Rejection.NO_TARGET);
        List<Object> members = mutableList(groups.get(row).get(MEMBER_MEMBERS));
        if (index < 0 || index >= members.size()) return EditResult.rejected(Rejection.INDEX_OUT_OF_RANGE);
        members.remove(index);
        groups.get(row).put(MEMBER_MEMBERS, members);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 原位替换成员（真编辑态提交）。
     *
     * @param key      行 key
     * @param index    成员下标
     * @param selector 新 selector
     * @return 命令结果
     */
    public EditResult replaceMember(long key, int index, String selector) {
        List<Map<String, Object>> groups = groups();
        int row = indexOfKey(groups, key);
        if (row < 0) return EditResult.rejected(Rejection.NO_TARGET);
        List<Object> members = mutableList(groups.get(row).get(MEMBER_MEMBERS));
        if (index < 0 || index >= members.size()) return EditResult.rejected(Rejection.INDEX_OUT_OF_RANGE);
        Rejection invalid = validateSelector(selector);
        if (invalid != Rejection.NONE) return EditResult.rejected(invalid);
        members.set(index, selector);
        groups.get(row).put(MEMBER_MEMBERS, members);
        clearRemoved();
        write(groups);
        return EditResult.ok();
    }

    /**
     * 移动组。
     *
     * @param key       行 key
     * @param direction -1 上移 / +1 下移
     * @return 命令结果
     */
    private EditResult moveGroup(long key, int direction) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        return moveGroupTo(key, index + direction);
    }

    /**
     * 把组移动到目标位次（越界自动 clamp；位次不变时视为成功且不写入草稿）。
     *
     * @param key    行 key
     * @param target 目标下标
     * @return 命令结果
     */
    private EditResult moveGroupTo(long key, int target) {
        List<Map<String, Object>> groups = groups();
        int index = indexOfKey(groups, key);
        if (index < 0) return EditResult.rejected(Rejection.NO_TARGET);
        int to = Math.max(0, Math.min(target, groups.size() - 1));
        if (to == index) return EditResult.ok();
        Map<String, Object> moved = groups.remove(index);
        groups.add(to, moved);
        clearRemoved();
        write(groups);
        selectedKey.set(Long.valueOf(key));
        return EditResult.ok();
    }

    /**
     * 复制组 id：{@code <id>_copy}，占用时追加序号；总长受 {@link ObjectGroup#MAX_ID_LENGTH} 约束。
     *
     * @param groups 当前组列表
     * @param base   原组 id
     * @return 未占用的新 id
     */
    private static String nextFreeCopyId(List<Map<String, Object>> groups, String base) {
        Set<String> used = new LinkedHashSet<String>();
        for (Map<String, Object> group : groups) used.add(stringValue(group.get(MEMBER_ID)));
        String stem = base;
        String suffix = "_copy";
        if (stem.length() + suffix.length() > ObjectGroup.MAX_ID_LENGTH) {
            stem = stem.substring(0, Math.max(0, ObjectGroup.MAX_ID_LENGTH - suffix.length()));
        }
        String candidate = stem + suffix;
        int i = 2;
        while (used.contains(candidate)) {
            String tail = suffix + i;
            String head = base.length() + tail.length() > ObjectGroup.MAX_ID_LENGTH
                    ? base.substring(0, Math.max(0, ObjectGroup.MAX_ID_LENGTH - tail.length())) : base;
            candidate = head + tail;
            i++;
        }
        return candidate;
    }

    // ------------------------------------------------------------------ 内部实现

    private void clearRemoved() {
        if (removedValue == null && !Boolean.TRUE.equals(canUndoRemove.get())) return;
        removedValue = null;
        canUndoRemove.set(Boolean.FALSE);
    }

    private List<Map<String, Object>> groups() {
        // 编辑前先把行投影对齐到最新草稿：视图打开期间配置页 reload/撤销后，命令必须基于最新值。
        syncExternal(draftSignal.get());
        Object raw = draftSignal.get();
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) return groups;
        for (Object item : (List<?>) raw) groups.add(copyGroup(item));
        return groups;
    }

    private static Map<String, Object> copyGroup(Object raw) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            group.put(MEMBER_ID, stringValue(map.get(MEMBER_ID)));
            group.put(MEMBER_MODES, mutableList(map.get(MEMBER_MODES)));
            group.put(MEMBER_MEMBERS, mutableList(map.get(MEMBER_MEMBERS)));
        } else {
            group.put(MEMBER_ID, "");
            group.put(MEMBER_MODES, new ArrayList<Object>());
            group.put(MEMBER_MEMBERS, new ArrayList<Object>());
        }
        return group;
    }

    private static List<Object> mutableList(Object raw) {
        List<Object> out = new ArrayList<Object>();
        if (raw instanceof List) out.addAll((List<?>) raw);
        return out;
    }

    private static String stringValue(Object raw) {
        return raw instanceof String ? (String) raw : (raw == null ? "" : String.valueOf(raw));
    }

    private static List<String> stringList(List<Object> raw) {
        List<String> out = new ArrayList<String>(raw.size());
        for (Object item : raw) out.add(stringValue(item));
        return out;
    }

    /**
     * 行 key → 组下标。
     *
     * <p>行投影与草稿列表同序（{@link StructuredListModel#sync} 保持输入顺序），因此直接用 rows
     * 的下标定位；不按 id 反查，避免出现非法重复 id 时改错行。</p>
     *
     * @param groups 当前草稿组列表
     * @param key    行 key
     * @return 下标，未命中为 -1
     */
    private int indexOfKey(List<Map<String, Object>> groups, long key) {
        List<StructuredListModel.Row> current = rows.get();
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).key() == key) return i < groups.size() ? i : -1;
        }
        return -1;
    }

    private void selectById(String id) {
        RowView view = viewOfId(id);
        select(view == null ? -1L : view.key());
    }

    private void write(List<Map<String, Object>> groups) {
        List<Object> value = new ArrayList<Object>(groups);
        rows.set(StructuredListModel.sync(rows.get(), value, objectSpec, lineage));
        adapter.onFieldEdit(path, value);
        parseCache = null;
        parseCacheRows = null;
    }

    private static Rejection validateSelector(String selector) {
        if (selector == null || selector.isEmpty()) return Rejection.SELECTOR_UNPARSABLE;
        if (selector.length() > ObjectGroupSelector.MAX_CANONICAL_LENGTH) return Rejection.SELECTOR_TOO_LONG;
        try {
            ObjectGroupParser.parseSelector(selector);
        } catch (RuntimeException e) {
            return Rejection.SELECTOR_UNPARSABLE;
        }
        return Rejection.NONE;
    }

    private static int totalMembers(List<Map<String, Object>> groups) {
        int total = 0;
        for (Map<String, Object> group : groups) total += mutableList(group.get(MEMBER_MEMBERS)).size();
        return total;
    }

    private static String nextFreeId(List<Map<String, Object>> groups) {
        Set<String> used = new LinkedHashSet<String>();
        for (Map<String, Object> group : groups) used.add(stringValue(group.get(MEMBER_ID)));
        for (int i = 1; i <= ObjectGroupRuleSet.MAX_GROUPS + 1; i++) {
            String candidate = "group_" + i;
            if (!used.contains(candidate)) return candidate;
        }
        return "group_" + (groups.size() + 1);
    }

    private static Set<String> knownModeIds() {
        Set<String> ids = new LinkedHashSet<String>();
        for (String id : ObjectGroupMode.ids()) ids.add(id);
        return ids;
    }

    private static List<Object> unknownModesOf(Map<String, Object> group) {
        Set<String> known = knownModeIds();
        List<Object> out = new ArrayList<Object>();
        for (Object mode : mutableList(group.get(MEMBER_MODES))) {
            if (!known.contains(stringValue(mode))) out.add(mode);
        }
        return out;
    }

    /**
     * 已知模式位掩码。
     *
     * <p>先用 {@link LinkedHashSet} 去重再交给 {@link ObjectGroupMode#toMask(Iterable)}：配置里若出现
     * 重复的模式值（手工编辑/旧版本残渣），{@code toMask} 会抛 {@code duplicate object group mode}，
     * 而 {@code views()} 是渲染路径，不能因非法草稿把整个列表炸掉（该行仍由解析器错误通道标红）。</p>
     *
     * @param modes 原始模式值
     * @return 已知位掩码；无已知模式时为 0
     */
    private static long knownMask(List<Object> modes) {
        Set<String> known = new LinkedHashSet<String>();
        Set<String> ids = knownModeIds();
        for (Object mode : modes) {
            String value = stringValue(mode);
            if (ids.contains(value)) known.add(value);
        }
        return known.isEmpty() ? 0L : ObjectGroupMode.toMask(known);
    }

    private List<RowView> buildViews(List<StructuredListModel.Row> current,
                                     ObjectGroupParser.ParseResult parse, long selected) {
        Map<Integer, RowError> rowErrors = rowErrors(parse);
        List<RowView> views = new ArrayList<RowView>(current.size());
        for (int i = 0; i < current.size(); i++) {
            StructuredListModel.Row row = current.get(i);
            String id = stringValue(row.get(MEMBER_ID));
            List<Object> modesRaw = mutableList(row.get(MEMBER_MODES));
            List<Object> membersRaw = mutableList(row.get(MEMBER_MEMBERS));
            Set<String> ids = knownModeIds();
            Set<String> knownModes = new LinkedHashSet<String>();
            for (Object mode : modesRaw) {
                String value = stringValue(mode);
                if (ids.contains(value)) knownModes.add(value);
            }
            List<String> modes = new ArrayList<String>(knownModes);
            List<String> unknown = stringList(unknownModesOf(row.value()));
            List<String> members = stringList(membersRaw);
            Set<Flag> flags = EnumSet.noneOf(Flag.class);
            RowError rowError = rowErrors.get(Integer.valueOf(i));
            // 两类错误分开标记：CONFLICT 只表示「模式+选择器重叠」，结构错误（id 重复、成员为空、
            // selector 非法）标 ERROR ⇒ 两者都能定位到行，但只有前者进 [冲突] 谓词与冲突计数。
            if (rowError != null) {
                if (rowError.conflict) flags.add(Flag.CONFLICT);
                else flags.add(Flag.ERROR);
            }
            if (knownMask(modesRaw) == 0L) flags.add(Flag.INACTIVE);
            if (membersRaw.isEmpty() || id.isEmpty()) flags.add(Flag.INCOMPLETE);
            boolean wildcard = false;
            for (Object member : membersRaw) {
                try {
                    if (ObjectGroupParser.parseSelector(stringValue(member)).specificity()
                            == ObjectGroupSelector.Specificity.WILDCARD) {
                        wildcard = true;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // 不可解析的成员由解析器错误通道报出，这里只做通配标记
                }
            }
            if (wildcard) flags.add(Flag.WILDCARD);
            views.add(new RowView(row.key(), i, id, modes, unknown, members, flags,
                    rowError == null ? null : rowError.message, row.key() == selected));
        }
        return views;
    }

    private static Map<Integer, RowError> rowErrors(ObjectGroupParser.ParseResult parse) {
        Map<Integer, RowError> errors = new LinkedHashMap<Integer, RowError>();
        if (parse == null) return errors;
        for (Map.Entry<String, String> entry : parse.errors().entrySet()) {
            Integer index = rowIndex(entry.getKey());
            boolean conflict = index != null && entry.getKey().endsWith(".modes");
            if (index == null) {
                // 单条结构性错误：ObjectGroupParser.ParseResult.invalid(String) 会把「带下标的错误路径」
                // 压成字段级键 client.objectGroups，路径信息只留在消息文本里
                // （例："client.objectGroups[0].id is duplicated"）⇒ 从 value 再取一次下标，
                // 使 id 重复/成员为空这类结构错误也能落到对应行（复核 D3）。
                index = rowIndex(entry.getValue());
                conflict = false;
            }
            if (index == null) continue;
            RowError previous = errors.get(index);
            if (previous == null) {
                errors.put(index, new RowError(entry.getValue(), conflict));
            } else if (!previous.message.equals(entry.getValue())) {
                errors.put(index, new RowError(entry.getValue(), previous.conflict || conflict));
            }
        }
        return errors;
    }

    private static Integer rowIndex(String path) {
        if (path == null) return null;
        Matcher matcher = ROW_PATH.matcher(path);
        if (!matcher.find()) return null;
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
