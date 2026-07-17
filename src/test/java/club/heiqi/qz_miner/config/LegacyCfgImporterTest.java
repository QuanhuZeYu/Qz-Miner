package club.heiqi.qz_miner.config;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.config.LegacyCfgImporter.ImportResult;

/**
 * ImportResult 状态机（纯 JVM；不依赖 Forge Configuration 成功路径）。
 */
public class LegacyCfgImporterTest {

    @Test
    public void missingFileReturnsMissingNotOkEmpty() {
        ImportResult result = LegacyCfgImporter.importValues(new File("definitely-missing-qz-miner.cfg"));
        Assert.assertEquals(ImportResult.Status.MISSING, result.status);
        Assert.assertTrue(result.values.isEmpty());
    }

    @Test
    public void nullFileReturnsMissing() {
        ImportResult result = LegacyCfgImporter.importValues(null);
        Assert.assertEquals(ImportResult.Status.MISSING, result.status);
    }
}
