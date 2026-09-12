package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.InputFrameBuilder;
import club.heiqi.uilib.ui.scene.input.RawInputEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/**
 * 组重命名身份保持回归（P0，既有缺陷）。
 *
 * <p><b>缺陷链</b>：详情 id 输入框每键入一个字符 → {@code renameGroup} 整表写草稿 →
 * {@code StructuredListModel.sync/appendByIdentity} 按 identity member「id」匹配不到新值 ⇒ 分配
 * 新 row key ⇒ 选中键失效、详情 keyed 列表整棵重建、输入框回收、焦点清空、后续字符全丢。</p>
 *
 * <p><b>判定口径</b>：行 key 是列表/详情两棵 keyed 列表的身份真值，故断言全部落在可观测量上——
 * 行节点身份（{@code forEach} 保 key 复用）、详情 id 区节点身份、焦点节点身份、草稿 id 文本与
 * 顺序/选中；状态层直接断言 {@code visibleViews().get(i).key()}。</p>
 */
public class ObjectGroupRenameIdentityTest {

    private static final int WIDE = 700;
    private static final int HEIGHT = 420;

    private File tempDir;
    private ConfigManager manager;
    private ObjectGroupEditorTestSupport.RendererFixture fixture;
    private ObjectGroupEditorTestSupport.Harness harness;
    private SceneNode card;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-rename-identity-").toFile();
        manager = ObjectGroupEditorTestSupport.bootstrap(tempDir);
        fixture = new ObjectGroupEditorTestSupport.RendererFixture(manager);
        harness = new ObjectGroupEditorTestSupport.Harness(WIDE, HEIGHT);
        card = fixture.renderer.render(harness.rt, fixture.spec, fixture.adapter);
        harness.root.appendChild(card);
        harness.frame();
    }

    @After
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
        if (fixture != null) {
            fixture.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        if (tempDir != null) {
            delete(tempDir);
        }
    }

    // ==================================================================
    // 状态层：身份（key）保持 + 拒绝守卫
    // ==================================================================

    @Test
    public void renameKeepsRowKeySelectionAndOrder() {
        ObjectGroupEditorState state = new ObjectGroupEditorState(fixture.spec, fixture.adapter);
        List<ObjectGroupEditorState.RowView> before = state.visibleViews();
        Assert.assertTrue("夹具至少 3 行", before.size() >= 3);
        long key = before.get(0).key();
        String renamed = before.get(0).id() + "_x";
        state.select(key);

        ObjectGroupEditorState.EditResult result = state.renameGroup(key, renamed);
        ReactiveScheduler.get().flush();

        List<ObjectGroupEditorState.RowView> after = state.visibleViews();
        StringBuilder keys = new StringBuilder();
        for (ObjectGroupEditorState.RowView view : after) {
            keys.append(view.key()).append(',');
        }
        System.out.println("[rename] key=" + key + " renamed=" + renamed + " afterKeys=" + keys
                + " selectedKey=" + state.selectedKey().get());
        Assert.assertTrue("改名必须被接受", result.accepted());
        Assert.assertEquals("行数不变", before.size(), after.size());
        Assert.assertEquals("改名不得换行 key", key, after.get(0).key());
        Assert.assertEquals("草稿 id 已更新", renamed, after.get(0).id());
        Assert.assertEquals("选中行仍存在（选中不丢）", key, state.selectedKey().get().longValue());
        Assert.assertNotNull("选中行可解析", state.selection());
        for (int i = 1; i < before.size(); i++) {
            Assert.assertEquals("其余行 key 顺序不变", before.get(i).key(), after.get(i).key());
            Assert.assertEquals("其余行 id 不变", before.get(i).id(), after.get(i).id());
        }
    }

    @Test
    public void renameRejectionsKeepDraftUntouched() {
        ObjectGroupEditorState state = new ObjectGroupEditorState(fixture.spec, fixture.adapter);
        List<ObjectGroupEditorState.RowView> before = state.visibleViews();
        ObjectGroupEditorState.RowView first = before.get(0);
        String otherId = before.get(1).id();
        long key = first.key();
        String originalId = first.id();
        state.select(key);

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= ObjectGroup.MAX_ID_LENGTH; i++) {
            tooLong.append('x');
        }
        ObjectGroupEditorState.Rejection[] expected = {
            ObjectGroupEditorState.Rejection.ID_EMPTY,
            ObjectGroupEditorState.Rejection.ID_DUPLICATED,
            ObjectGroupEditorState.Rejection.ID_TOO_LONG,
        };
        String[] inputs = {"", otherId, tooLong.toString()};
        for (int i = 0; i < inputs.length; i++) {
            ObjectGroupEditorState.EditResult result = state.renameGroup(key, inputs[i]);
            Assert.assertFalse("必须拒绝: " + expected[i], result.accepted());
            Assert.assertEquals("拒绝原因", expected[i], result.rejection());
        }
        ReactiveScheduler.get().flush();

        System.out.println("[rename] 拒绝后 draftId=" + state.visibleViews().get(0).id()
                + " key=" + state.visibleViews().get(0).key()
                + " selectedKey=" + state.selectedKey().get());
        Assert.assertEquals("被拒命令不得改草稿 id", originalId, state.visibleViews().get(0).id());
        Assert.assertEquals("被拒命令不得换 key", key, state.visibleViews().get(0).key());
        Assert.assertEquals("被拒命令不得丢选中", key, state.selectedKey().get().longValue());
    }

    @Test
    public void renameKeepsParseFlagsAndOtherRows() {
        ObjectGroupEditorState state = new ObjectGroupEditorState(fixture.spec, fixture.adapter);
        Map<String, String> errorsBefore = new HashMap<String, String>(state.parseResult().errors());
        List<Boolean> conflictBefore = flagsOf(state, ObjectGroupEditorState.Flag.CONFLICT);
        List<Boolean> errorBefore = flagsOf(state, ObjectGroupEditorState.Flag.ERROR);
        List<ObjectGroupEditorState.RowView> before = state.visibleViews();
        long key = before.get(0).key();

        Assert.assertTrue(state.renameGroup(key, before.get(0).id() + "_renamed").accepted());
        ReactiveScheduler.get().flush();

        System.out.println("[rename] parseErrors before=" + errorsBefore
                + " after=" + state.parseResult().errors());
        Assert.assertEquals("改名不得改变整表解析错误集合",
                errorsBefore, new HashMap<String, String>(state.parseResult().errors()));
        Assert.assertEquals("CONFLICT 行标记不回退", conflictBefore,
                flagsOf(state, ObjectGroupEditorState.Flag.CONFLICT));
        Assert.assertEquals("ERROR 行标记不回退", errorBefore,
                flagsOf(state, ObjectGroupEditorState.Flag.ERROR));
    }

    // ==================================================================
    // 端到端：详情 id 输入框连续键入
    // ==================================================================

    @Test
    public void idInputKeepsFocusAndAppendsAcrossKeystrokes() {
        openEditor();
        String originalId = stateSnapshot().visibleViews().get(0).id();
        SceneNode rowTitle = ObjectGroupEditorTestSupport.findText(harness.editorRoot(), originalId);
        Assert.assertNotNull("列表行缺失", rowTitle);
        harness.click(rowTitle);
        harness.frame();

        SceneNode rowNode = rowRootOf(rowTitle);
        Assert.assertNotNull("行根缺失（id 文本向上找 borderWidth==1）", rowNode);
        SceneNode input = idInput();
        SceneNode section = input.__getParent();
        harness.click(input);
        harness.frame();
        harness.pressKey(SceneKey.END);
        harness.frame();
        Assert.assertSame("点击后焦点必须落在 id 输入框", input, harness.focused());

        StringBuilder typed = new StringBuilder();
        String[] chars = {"a", "b", "c"};
        for (int i = 0; i < chars.length; i++) {
            typeText(chars[i]);
            harness.frame();
            typed.append(chars[i]);
            String draftId = stateSnapshot().visibleViews().get(0).id();
            SceneNode labelNow = ObjectGroupEditorTestSupport.findText(harness.editorRoot(),
                    ClientI18n.tr("config.qz_miner.object_group.id.label"));
            boolean sameSection = labelNow != null && labelNow.__getParent() == section;
            System.out.println("[rename] type=" + typed + " draftId=" + draftId
                    + " focusSame=" + (harness.focused() == input)
                    + " detailSectionSame=" + sameSection
                    + " rowNodeAttached=" + (rowNode.__getParent() != null)
                    + " rowSelected=" + (rowNode.getBackgroundColor() != 0));
            Assert.assertEquals("草稿 id 必须连续追加", originalId + typed, draftId);
            Assert.assertSame("焦点必须留在同一输入节点", input, harness.focused());
            Assert.assertTrue("详情 id 区不得整棵重建", sameSection);
            Assert.assertTrue("列表行节点必须复用（key 不变）", rowNode.__getParent() != null);
            Assert.assertTrue("行选中不得丢失", rowNode.getBackgroundColor() != 0);
        }

        List<ObjectGroupEditorState.RowView> after = stateSnapshot().visibleViews();
        Assert.assertEquals("顺序不变（首行仍是同一组）", originalId + typed, after.get(0).id());
        Assert.assertEquals("行数不变", 3, after.size());
    }

    @Test
    public void idInputShowsInlineErrorForEmptyAndDuplicate() {
        openEditor();
        List<ObjectGroupEditorState.RowView> before = stateSnapshot().visibleViews();
        String originalId = before.get(0).id();
        String otherId = before.get(1).id();
        SceneNode rowTitle = ObjectGroupEditorTestSupport.findText(harness.editorRoot(), originalId);
        Assert.assertNotNull("列表行缺失", rowTitle);
        harness.click(rowTitle);
        harness.frame();
        SceneNode input = idInput();
        harness.click(input);
        harness.frame();
        harness.pressKey(SceneKey.END);
        harness.frame();

        for (int i = 0; i < originalId.length(); i++) {
            harness.pressKey(SceneKey.BACKSPACE);
        }
        harness.frame();
        boolean emptyHint = ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.id.empty"));
        String draftAfterClear = stateSnapshot().visibleViews().get(0).id();
        System.out.println("[rename] 清空后 emptyHint=" + emptyHint
                + " draftId=" + draftAfterClear);
        Assert.assertTrue("空 id 必须给行内提示", emptyHint);
        Assert.assertFalse("被拒的空 id 不得写入草稿", draftAfterClear.isEmpty());
        Assert.assertTrue("草稿必须仍是被接受过的 id 前缀（逐字删除各自被接受，空值被拒）",
                originalId.startsWith(draftAfterClear));

        for (int i = 0; i < otherId.length(); i++) {
            typeText(String.valueOf(otherId.charAt(i)));
        }
        harness.frame();
        boolean duplicateHint = ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.id.duplicated"));
        String lastAcceptedPrefix = otherId.substring(0, otherId.length() - 1);
        System.out.println("[rename] 输入重复 id 后 duplicateHint=" + duplicateHint
                + " draftId=" + stateSnapshot().visibleViews().get(0).id());
        Assert.assertTrue("重复 id 必须给行内提示", duplicateHint);
        Assert.assertNotEquals("重复 id 不得写入草稿", otherId,
                stateSnapshot().visibleViews().get(0).id());
        Assert.assertEquals("重复 id 被拒：草稿停在最后一次被接受的前缀", lastAcceptedPrefix,
                stateSnapshot().visibleViews().get(0).id());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void openEditor() {
        SceneNode manage = ObjectGroupEditorTestSupport.findText(card,
                ClientI18n.tr("config.qz_miner.object_group.manage"));
        Assert.assertNotNull("摘要卡入口按钮缺失", manage);
        harness.click(manage);
        Assert.assertNotNull(harness.editorRoot());
        harness.frame();
    }

    /** 详情 id 输入控件根：id 标签（section 首子）→ 同列第 2 子。 */
    private SceneNode idInput() {
        SceneNode label = ObjectGroupEditorTestSupport.findText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.id.label"));
        Assert.assertNotNull("详情 id 标签缺失", label);
        List<SceneNode> children = label.__getParent().__getChildren();
        Assert.assertTrue("详情 id 区结构异常: " + children.size(), children.size() >= 2);
        return children.get(1);
    }

    /** 行根：id 文本向上找首个 borderWidth==1 的整卡节点（列表 keyed 列表的身份节点）。 */
    private static SceneNode rowRootOf(SceneNode title) {
        SceneNode cursor = title;
        for (int i = 0; i < 6 && cursor != null; i++) {
            if (cursor.getBorderWidth() == 1) {
                return cursor;
            }
            cursor = cursor.__getParent();
        }
        return null;
    }

    private ObjectGroupEditorState stateSnapshot() {
        return new ObjectGroupEditorState(fixture.spec, fixture.adapter);
    }

    private static List<Boolean> flagsOf(ObjectGroupEditorState state, ObjectGroupEditorState.Flag flag) {
        List<Boolean> flags = new ArrayList<Boolean>();
        for (ObjectGroupEditorState.RowView view : state.visibleViews()) {
            flags.add(Boolean.valueOf(view.hasFlag(flag)));
        }
        return flags;
    }

    /** 直接注入文本事件（模拟连续键入）。 */
    private void typeText(String text) {
        InputFrameBuilder builder = new InputFrameBuilder(0, 0);
        builder.push(RawInputEvent.ofText(text, 2_000_000L));
        harness.rt.route(harness.root, builder.drainFrame(), 0, 0);
        harness.frame();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
