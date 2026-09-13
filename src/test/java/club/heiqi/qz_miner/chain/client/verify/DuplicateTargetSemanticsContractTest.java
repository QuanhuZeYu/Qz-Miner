package club.heiqi.qz_miner.chain.client.verify;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T43 波次 9 重复目标语义契约（B5.4：维持现状 + 文档化）。
 *
 * <p>行为口径由独立构造的序列锁定：**上游计数按收到次数（含重复）**，而**几何/配额去重**
 * （B0.7 去重先于配额）。使用文档必须与这一口径一致——写反按缺陷处理。</p>
 */
public class DuplicateTargetSemanticsContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);

    private static List<ChainTarget> collect(Iterable<ChainTarget> targets) {
        List<ChainTarget> list = new ArrayList<ChainTarget>();
        for (ChainTarget target : targets) {
            list.add(target);
        }
        return list;
    }

    @Test
    public void upstreamCountsIncludeDuplicatesWhileGeometryDedups() {
        ChainTarget repeated = new ChainTarget(7, 8, 9);
        ChainTarget other = new ChainTarget(12, 8, 9);
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(repeated);
        Assert.assertTrue(state.addPreviewTarget(generation, repeated));
        Assert.assertTrue(state.addPreviewTarget(generation, repeated));
        Assert.assertTrue(state.addPreviewTarget(generation, other));

        Assert.assertEquals("HUD 计数口径：收到的每个目标（含重复）都计入", 3, state.getMatchedCount());
        List<ChainTarget> snapshotOrder = collect(state.captureRenderSnapshot().getTargets());
        Assert.assertEquals("快照必须保留重复项（最新→最早）", 3, snapshotOrder.size());

        // 几何去重：同快照喂入构建器只产出 2 个块
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            snapshotOrder, VISUALS, 0.045F, null);
        Assert.assertEquals("几何必须去重为唯一位置数", 2, mesh.getBlockCount());
        Assert.assertFalse("重复输入不得触发容量截断（去重先于配额）", mesh.isTruncated());
    }

    @Test
    public void duplicatesDoNotConsumeGeometryQuotaEvenWhenUpstreamCountExceedsLimit() {
        List<ChainTarget> many = new ArrayList<ChainTarget>();
        int duplicates = ChainPreviewMeshBuilder.MAX_RENDER_TARGETS + 256;
        for (int index = 0; index < duplicates; index++) {
            many.add(new ChainTarget(1, 2, 3));
        }
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(many, VISUALS, 0.045F, null);
        Assert.assertEquals(1, mesh.getBlockCount());
        Assert.assertFalse("重复不得消耗配额", mesh.isTruncated());
    }

    @Test
    public void usageDocumentationMatchesTheLockedSemantics() throws Exception {
        String doc = readUsageDocument();
        boolean mentionsDuplicates = doc.contains("重复");
        Assert.assertTrue("使用文档必须写明重复目标语义（B5.4）", mentionsDuplicates);

        int previewSection = doc.indexOf("预览");
        Assert.assertTrue("使用文档必须包含预览章节", previewSection >= 0);
        String previewText = doc.substring(previewSection);

        boolean mentionsCounting = previewText.contains("计数") || previewText.contains("HUD")
            || previewText.contains("数字");
        boolean mentionsQuotaOrDedup = previewText.contains("配额") || previewText.contains("去重")
            || previewText.contains("容量") || previewText.contains("唯一");
        Assert.assertTrue("文档必须写明计数口径（含重复计入）", mentionsCounting);
        Assert.assertTrue("文档必须写明几何/配额口径（去重）", mentionsQuotaOrDedup);

        String[] reversedClaims = {
            "重复目标不计数",
            "重复目标不会被计数",
            "重复目标被完全忽略",
            "重复目标会重复占用配额",
            "重复目标重复占容量"
        };
        for (String claim : reversedClaims) {
            Assert.assertFalse("文档出现与代码行为相反的表述：" + claim, doc.contains(claim));
        }
    }

    private static String readUsageDocument() throws Exception {
        File file = new File("docs/使用文档/README.md");
        Assert.assertTrue("使用文档必须存在: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    @Test
    public void snapshotOrderKeepsNewestFirstWithDuplicates() {
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(3, 0, 0);
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(first);
        state.addPreviewTarget(generation, first);
        state.addPreviewTarget(generation, second);
        state.addPreviewTarget(generation, second);
        List<ChainTarget> order = collect(state.captureRenderSnapshot().getTargets());
        Assert.assertEquals(Arrays.asList(second, second, first), order);
    }
}
