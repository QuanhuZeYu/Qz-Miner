package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.uilib.api.chat.ChatAction;
import club.heiqi.uilib.api.chat.ChatActionRegistration;
import club.heiqi.uilib.api.chat.ChatActionService;
import club.heiqi.uilib.ui.hud.api.HudAnchor;
import club.heiqi.uilib.ui.hud.api.HudEditService;
import club.heiqi.uilib.ui.hud.api.HudEditTarget;
import club.heiqi.uilib.ui.hud.api.HudPlacement;
import club.heiqi.uilib.ui.hud.api.HudRegistration;
import club.heiqi.uilib.ui.reactive.Signal;

/**
 * 连锁状态 HUD 的编辑入口唯一接线点（UILib 4.9.1 公开 HUD 编辑契约）。
 *
 * <p>两件注册，互相独立：</p>
 * <ol>
 *   <li>{@link HudEditService#register(HudEditTarget)}：把 {@link QzMinerHudWindow#HUD_ID}
 *       声明为可编辑目标——聊天输入屏进入编辑子模式后为该目标渲染预览浮层、命中拖动，
 *       位置写 UILib 的布局草稿；</li>
 *   <li>{@link ChatActionService#register(ChatAction)}：聊天工具栏「编辑 HUD」按钮，
 *       点击只发布 {@code HudEditService.requestEdit(HUD_ID)} 意图，由当前打开的聊天屏消费
 *       （无活动聊天屏时 UILib 静默丢弃，不排队、不抛异常）。</li>
 * </ol>
 *
 * <p><b>注册契约</b>：注册单点（{@code ClientProxy.init} 调用 {@link #install}）、幂等
 * （以 UILib 注册表实际内容为准，重复 install 不重复注册、不抛异常）、失败隔离
 * （任一注册失败只丢该件并告警，HUD 主体与另一件不受影响）、断线不重注册
 * （句柄常驻静态字段，不 close；重连只重建宿主窗口，不重放注册）。</p>
 *
 * <p><b>边界</b>：放置/拖动/夹取数学全部归 UILib（{@code HudLayoutService} 与编辑宿主），
 * 本类只声明事实；Miner 不自绘编辑或缩放按钮。全部方法限客户端主线程。</p>
 */
public final class QzMinerHudEditEntry {

    /** 聊天工具栏动作 id（全局唯一）。 */
    public static final String ACTION_ID = "qz_miner:hud_edit";

    /**
     * 动作排序值：与 UILib 内置「编辑 HUD」同档（1000）——普通动作默认 0，
     * 编辑类入口集中在末尾，同档内按注册序排列。
     */
    public static final int ACTION_ORDER = 1000;

    /** 可编辑目标注册句柄；断线/世界切换保持有效，不 close。 */
    private static HudRegistration editTargetRegistration;
    /** 聊天工具栏动作注册句柄；跨聊天开关保持注册，不 close。 */
    private static ChatActionRegistration chatActionRegistration;

    private QzMinerHudEditEntry() {
    }

    /**
     * 安装编辑入口（{@code ClientProxy.init} 单点调用；重复调用幂等）。
     *
     * @param window 连锁状态 HUD 窗口（提供编辑期预览工厂）
     */
    public static void install(QzMinerHudWindow window) {
        editTargetRegistration = registerEditTarget(window);
        chatActionRegistration = registerChatAction();
    }

    /**
     * 注册可编辑目标（幂等 + 失败隔离）。
     *
     * @return 注册句柄；已注册或注册失败返回既有句柄 / null（调用方无需处理）
     */
    static HudRegistration registerEditTarget(QzMinerHudWindow window) {
        if (HudEditService.getInstance().hasTarget(QzMinerHudWindow.HUD_ID)) {
            return editTargetRegistration;
        }
        try {
            return HudEditService.getInstance().register(buildTarget(window));
        } catch (RuntimeException failure) {
            MyMod.LOG.warn("[HudEdit] 可编辑目标注册失败，已隔离（HUD 主体不受影响）", failure);
            return null;
        }
    }

    /**
     * 构建可编辑目标（纯构造，不触碰注册表；测试可直接断言）。
     *
     * <p>默认放置取与关闭态 HUD 相同的锚点与边距（{@link QzMinerHudWindow#HUD_MARGIN_PX}），
     * 预览因此从 HUD 的默认位置起步，不出现「一进编辑就跳位」。</p>
     *
     * <p><b>不声明 toolbarSpec</b>：关闭态 HUD 不挂常驻工具栏（无内容时整窗隐藏、工具栏不可见），
     * 缩放工具改由 UILib 编辑层在编辑子模式统一提供；Miner 因此只声明预览与默认放置。</p>
     */
    static HudEditTarget buildTarget(QzMinerHudWindow window) {
        return HudEditTarget.builder(QzMinerHudWindow.HUD_ID)
                .previewFactory(window.previewFactory())
                .defaultPlacement(HudPlacement.defaultOf(HudAnchor.TOP_LEFT, QzMinerHudWindow.HUD_MARGIN_PX))
                .build();
    }

    /**
     * 注册聊天工具栏「编辑 HUD」动作（幂等 + 失败隔离）。
     *
     * <p>幂等以 UILib 注册表实际内容为准（同 {@code ChatHudEditIntent} 口径）：即使外部
     * 调用过 {@code ChatActionService.clear()}，本方法也能把入口重新装回而不重复。</p>
     *
     * @return 注册句柄；已注册或注册失败返回既有句柄 / null
     */
    static ChatActionRegistration registerChatAction() {
        for (ChatAction existing : ChatActionService.getInstance().actions()) {
            if (ACTION_ID.equals(existing.getId())) {
                return chatActionRegistration;
            }
        }
        try {
            return ChatActionService.getInstance().register(buildAction());
        } catch (RuntimeException failure) {
            MyMod.LOG.warn("[HudEdit] 聊天工具栏编辑入口注册失败，已隔离", failure);
            return null;
        }
    }

    /**
     * 构建「编辑 HUD」动作（测试可直接取用）。
     *
     * <p>label/tooltip 走 {@link ClientI18n} 既有口径（{@code hud.qz_miner.edit_action.*}，
     * 中英都在 {@code assets/qz_miner/lang} 提供）；action 只发布编辑意图，不做任何布局/渲染。</p>
     */
    static ChatAction buildAction() {
        return ChatAction.builder(ACTION_ID)
                .label(ClientI18n.tr("hud.qz_miner.edit_action.label"))
                .tooltip(ClientI18n.tr("hud.qz_miner.edit_action.tooltip"))
                .order(ACTION_ORDER)
                .visible(Signal.create(Boolean.TRUE))
                .enabled(Signal.create(Boolean.TRUE))
                .action(QzMinerHudEditEntry::requestEdit)
                .build();
    }

    /** 发布「进入编辑并聚焦连锁状态 HUD」意图（无活动编辑宿主时 UILib 静默丢弃）。 */
    static void requestEdit() {
        HudEditService.getInstance().requestEdit(QzMinerHudWindow.HUD_ID);
    }
}
