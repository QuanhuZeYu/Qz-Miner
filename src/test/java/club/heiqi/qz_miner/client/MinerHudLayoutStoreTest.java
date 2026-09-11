package club.heiqi.qz_miner.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link MinerHudLayoutStore} 的 headless 用例（对齐 task-11 冻结规格第 8 节的宿主契约）。
 *
 * <p>不依赖 Minecraft：只用临时目录 + 文件系统。</p>
 */
public class MinerHudLayoutStoreTest {

    /** 含制表符与换行的文本载荷：宿主必须原样往返，不做解析或裁剪。 */
    private static final String SAMPLE_TEXT = "first-line\nsecond\twith\ttabs\n";

    private Path root;
    private File target;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("qz-miner-hud-layout");
        target = new File(root.toFile(), MinerHudLayoutStore.FILE_NAME);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(root.toFile());
    }

    @Test
    public void loadReturnsEmptyStringWhenFileMissing() {
        MinerHudLayoutStore store = new MinerHudLayoutStore(root.toFile());
        Assert.assertEquals("", store.load());
    }

    @Test
    public void saveThenLoadRoundTripsRawTextAndCreatesParent() throws IOException {
        File nestedDir = new File(root.toFile(), "nested");
        MinerHudLayoutStore store = new MinerHudLayoutStore(nestedDir);

        store.save(SAMPLE_TEXT);

        Assert.assertTrue("原子写必须自动建父目录", new File(nestedDir, MinerHudLayoutStore.FILE_NAME).isFile());
        Assert.assertEquals("往返必须逐字节原样（不裁剪、不重排）", SAMPLE_TEXT, store.load());
    }

    @Test
    public void saveOverwritesPreviousContentCompletely() {
        MinerHudLayoutStore store = new MinerHudLayoutStore(root.toFile());

        store.save("old-content");
        store.save("new-content");

        Assert.assertEquals("new-content", store.load());
    }

    @Test
    public void saveAndLoadDoNotThrowWhenTargetPathIsADirectory() throws IOException {
        Files.createDirectory(target.toPath());
        MinerHudLayoutStore store = new MinerHudLayoutStore(root.toFile());

        store.save(SAMPLE_TEXT);

        Assert.assertEquals("路径不是普通文件时按无数据返回", "", store.load());
        Assert.assertTrue("失败不得破坏原路径", target.isDirectory());
    }

    @Test
    public void nullTextIsWrittenAsEmptyText() {
        MinerHudLayoutStore store = new MinerHudLayoutStore(root.toFile());

        store.save(null);

        Assert.assertEquals("", store.load());
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(file.toPath());
    }
}
