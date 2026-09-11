package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.uilib.api.chat.ChatAction;
import club.heiqi.uilib.api.chat.ChatActionService;
import club.heiqi.uilib.ui.hud.api.HudAnchor;
import club.heiqi.uilib.ui.hud.api.HudEditService;
import club.heiqi.uilib.ui.hud.api.HudEditTarget;
import club.heiqi.uilib.ui.hud.api.HudPlacement;
import club.heiqi.uilib.ui.reactive.ReadableSignal;

/**
 * 回归 UILib 4.9.1 公开编辑契约的 Miner 接入：可编辑目标注册（单点/幂等/失败隔离）、
 * 默认放置与工具栏规格复用、聊天工具栏「编辑 HUD」动作的注册与触发链路。
 *
 * <p>headless：只用 UILib 公开单例注册表（纯 Java 注册表 + Signal）与自建窗口，
 * 不依赖 Minecraft 运行态；编辑意图经公开 {@link HudEditService.Host} 端口注入捕获，
 * 不 mock 生产 action，验证的是真实链路 {@code ChatAction.run() → requestEdit(HUD_ID)}。</p>
 */
public class QzMinerHudEditEntryTest {

    private QzMinerHudWindow window;

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    /** 每个用例从干净注册表起步（UILib 服务的 clear 即为此设计）。 */
    @Before
    public void resetRegistries() {
        HudEditService.getInstance().clear();
        ChatActionService.getInstance().clear();
        window = new QzMinerHudWindow(new ChainClientState(), new ClientPhaseProjection(),
                new QzMinerHudModel.PreviewStateSource() {
                    @Override
                    public ChainPreviewState current() {
                        return null;
                    }
                });
    }

    @After
    public void cleanupRegistries() {
        HudEditService.getInstance().clear();
        ChatActionService.getInstance().clear();
    }

    @Test
    public void installRegistersEditTargetAndChatActionExactlyOnce() {
        QzMinerHudEditEntry.install(window);
        QzMinerHudEditEntry.install(window);

        Assert.assertTrue("HUD 必须注册为可编辑目标",
                HudEditService.getInstance().hasTarget(QzMinerHudWindow.HUD_ID));
        Assert.assertEquals("重复 install 不得重复注册目标", 1,
                HudEditService.getInstance().targets().size());
        Assert.assertEquals(QzMinerHudWindow.HUD_ID,
                HudEditService.getInstance().targets().get(0).getHudId());
        Assert.assertEquals("聊天工具栏编辑入口只注册一次", 1,
                chatActions(QzMinerHudEditEntry.ACTION_ID).size());
        Assert.assertEquals(1, ChatActionService.getInstance().actions().size());
    }

    @Test
    public void editTargetCarriesDefaultPlacementAndLeavesScalingToEditLayer() {
        QzMinerHudEditEntry.install(window);

        HudEditTarget target = HudEditService.getInstance().target(QzMinerHudWindow.HUD_ID);
        Assert.assertNotNull(target);
        Assert.assertNotNull("预览工厂必填（builder 缺失即失败）", target.getPreviewFactory());
        Assert.assertNull("关闭态不挂常驻工具栏：目标不得声明 preview toolbarSpec（缩放归 UILib 编辑层）",
                target.getToolbarSpec());

        HudPlacement placement = target.getDefaultPlacement();
        Assert.assertEquals("默认放置锚点必须与关闭态 HUD 一致", HudAnchor.TOP_LEFT, placement.getAnchor());
        Assert.assertEquals("默认放置边距必须与 HudSpec.margin 同值",
                QzMinerHudWindow.HUD_MARGIN_PX, placement.getOffsetX());
        Assert.assertEquals(QzMinerHudWindow.HUD_MARGIN_PX, placement.getOffsetY());
    }

    @Test
    public void chatActionPublishesEditIntentForChainStatusHud() {
        QzMinerHudEditEntry.install(window);
        ChatAction action = chatActions(QzMinerHudEditEntry.ACTION_ID).get(0);

        Assert.assertEquals(QzMinerHudEditEntry.ACTION_ID, action.getId());
        Assert.assertFalse("label 走 ClientI18n，不得为空", action.getLabel().isEmpty());
        Assert.assertNotNull("tooltip 走 ClientI18n", action.getTooltip());
        Assert.assertFalse(action.getTooltip().isEmpty());
        Assert.assertEquals(QzMinerHudEditEntry.ACTION_ORDER, action.getOrder());
        Assert.assertEquals(Boolean.TRUE, action.getVisible().get());
        Assert.assertEquals(Boolean.TRUE, action.getEnabled().get());

        final List<String> entered = new ArrayList<String>();
        HudEditService.Host host = new HudEditService.Host() {
            @Override
            public void requestEnterEdit(String hudId) {
                entered.add(hudId);
            }

            @Override
            public boolean isEditing() {
                return true;
            }

            @Override
            public ReadableSignal<String> focus() {
                return () -> null;
            }
        };
        HudEditService.getInstance().attachHost(host);
        try {
            action.run();
        } finally {
            HudEditService.getInstance().detachHost(host);
        }

        Assert.assertEquals("点击编辑按钮必须聚焦连锁状态 HUD",
                Arrays.asList(QzMinerHudWindow.HUD_ID), entered);
    }

    @Test
    public void editIntentIsDroppedSilentlyWithoutActiveChatScreen() {
        QzMinerHudEditEntry.install(window);
        ChatAction action = chatActions(QzMinerHudEditEntry.ACTION_ID).get(0);

        Assert.assertFalse(HudEditService.getInstance().isEditing());
        action.run();
        Assert.assertFalse("无活动编辑宿主：意图静默丢弃，不得改变编辑态",
                HudEditService.getInstance().isEditing());
        Assert.assertNull(HudEditService.getInstance().focus().get());
    }

    @Test
    public void registrationFailureIsIsolatedAndReinstallRemainsPossible() {
        Assert.assertNull("目标构建异常必须被隔离，不向外抛出",
                QzMinerHudEditEntry.registerEditTarget(null));
        Assert.assertFalse("失败不得留下半注册状态",
                HudEditService.getInstance().hasTarget(QzMinerHudWindow.HUD_ID));

        QzMinerHudEditEntry.install(window);
        Assert.assertTrue(HudEditService.getInstance().hasTarget(QzMinerHudWindow.HUD_ID));
        Assert.assertEquals(1, chatActions(QzMinerHudEditEntry.ACTION_ID).size());
    }

    @Test
    public void reinstallAfterExternalClearRestoresEntryWithoutDuplicates() {
        QzMinerHudEditEntry.install(window);

        ChatActionService.getInstance().clear();
        QzMinerHudEditEntry.install(window);

        Assert.assertEquals("外部 clear 后重装仍必须只有一份入口", 1,
                chatActions(QzMinerHudEditEntry.ACTION_ID).size());
        Assert.assertEquals(1, HudEditService.getInstance().targets().size());
    }

    private static List<ChatAction> chatActions(String id) {
        List<ChatAction> result = new ArrayList<ChatAction>();
        for (ChatAction action : ChatActionService.getInstance().actions()) {
            if (id.equals(action.getId())) {
                result.add(action);
            }
        }
        return result;
    }
}
