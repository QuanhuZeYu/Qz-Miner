package club.heiqi.qz_miner.client.configGUI.objectgroup;

import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.editor.Registry;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 对象组编辑视图的宿主上下文（契约冻结：M1 实现，M2/M3/M4/M5 只消费）。
 *
 * <p>本接口是本包内唯一的跨模块接缝：{@link ObjectGroupEditorFieldRenderer} 在配置页字段里实现它，
 * 编辑视图与各 pane 通过它拿到运行时、草稿适配器、picker 注册表、状态与关闭入口。
 * 除本接口与 {@link ObjectGroupEditorState} 的公开签名外，pane 之间不得互相依赖具体实现类。</p>
 *
 * <h3>冻结的 pane 签名（实现方必须逐字匹配）</h3>
 * <pre>
 * ObjectGroupEditorView.build(ObjectGroupEditorContext ctx)            // M2 骨架根节点
 * ObjectGroupListPane.build(ObjectGroupEditorContext ctx)              // M3 组列表
 * ObjectGroupDetailPane.build(ObjectGroupEditorContext ctx)            // M4 组详情（读 state.selection）
 * ObjectGroupMemberPane.build(ObjectGroupEditorContext ctx, long key)  // M5 成员区（单组）
 * </pre>
 *
 * <p>全部为静态工厂，返回可直接 appendChild 的 {@code SceneNode}；构造期允许使用
 * {@code rt.mount / rt.bind / rt.forEach}，其绑定归调用方 runtime 的当前 Owner。</p>
 */
public interface ObjectGroupEditorContext {

    /**
     * 当前场景运行时。
     *
     * @return runtime，永不为 null
     */
    SceneRuntime rt();

    /**
     * 配置草稿适配器（唯一提交点 {@link DraftSignalAdapter#onFieldEdit}）。
     *
     * @return adapter，永不为 null
     */
    DraftSignalAdapter adapter();

    /**
     * 本 screen 已冻结的 value editor registry（成员 picker 的候选源来自这里）。
     *
     * @return registry，永不为 null
     */
    Registry editorRegistry();

    /**
     * 编辑状态与编辑事务。
     *
     * @return state，永不为 null
     */
    ObjectGroupEditorState state();

    /**
     * 请求关闭编辑视图（与 ESC 同一语义：只写可见性 signal，不直接挂卸浮层）。
     *
     * <p>供视图内的「完成」「← 返回」等控件调用；ESC/浮层 dismiss 走
     * {@link #setDismissHandler(BooleanSupplier)} 注册的处理器。</p>
     */
    void requestClose();

    /**
     * 注册「关闭请求」处理器（视图构建期调用一次）。
     *
     * <p>浮层策略为 ESC-only 时，ESC 由 {@code SceneInputRouter} 消费并调用本视图的 dismissRequest；
     * 处理器返回 {@code true} 表示视图已自行处理该请求（窄挡下钻中 → 返回列表），浮层保持打开；
     * 返回 {@code false} 则关闭视图。</p>
     *
     * @param handler 处理器，不可为 null
     */
    void setDismissHandler(BooleanSupplier handler);

    /**
     * 注册「行激活」处理器（M3 列表在指针点击某行后调用；M2 视图构建期注入一次）。
     *
     * <p>语义由视图决定：<b>窄挡</b> → 下钻到详情；<b>宽挡</b> → 不改焦点（详情已并排显示，
     * 指针点击后仍应能用 ↑/↓ 继续在列表里移动选中）。键盘 Enter 的语义不在这里
     * （见 M2 在列表根上的 KEY_DOWN 处理）。</p>
     *
     * @param handler 处理器，不可为 null
     */
    void setRowActivateHandler(Runnable handler);

    /**
     * 激活当前选中行（列表行被指针点击时调用；视图未注入处理器时为 no-op）。
     */
    void rowActivate();

    /**
     * 注册「选择移动」处理器（M3 列表构建期注入；M2 视图在浮层根上转发 ↑/↓ 时调用）。
     *
     * <p>处理器由列表实现（含滚动跟随），因此「焦点在详情时 ↑/↓ 仍能换组」不需要视图
     * 了解列表内部的视口与滚动状态。</p>
     *
     * @param handler 处理器，不可为 null
     */
    void setSelectionNudgeHandler(IntConsumer handler);

    /**
     * 移动列表选中项（delta = ±1；列表未挂载或不可移动时为 no-op）。
     *
     * @param delta 方向
     * @return true 表示列表已处理该请求
     */
    boolean nudgeSelection(int delta);

    /**
     * 处理一次关闭请求（由本渲染器注册给 portal 的 dismissRequest 调用；pane 不应直接调用）。
     */
    void handleDismissRequest();
}
