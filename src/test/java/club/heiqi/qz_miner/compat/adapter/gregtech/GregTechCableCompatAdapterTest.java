package club.heiqi.qz_miner.compat.adapter.gregtech;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** GT 反射能力档案的 headless 边界测试。 */
public class GregTechCableCompatAdapterTest {

    /** 缺少任一必需类型时完整能力档案必须关闭。 */
    @Test
    public void missingRequiredTypeDegradesCapability() {
        GregTechCableCompatAdapter.CapabilityProfile profile =
            GregTechCableCompatAdapter.CapabilityProfile.resolve(name -> null);
        assertFalse(new GregTechCableCompatAdapter(profile).isAvailable());
    }

    /** 适配器源码不得恢复 GT 静态链接或仅新版本客户端刷新入口。 */
    @Test
    public void sourceKeepsOptionalDependencyBoundary() throws Exception {
        Path source = Paths.get("src/main/java/club/heiqi/qz_miner/compat/adapter/gregtech/GregTechCableCompatAdapter.java");
        String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(content.contains("import gregtech."));
        assertFalse(content.contains("issueClientUpdate"));
        assertTrue(content.contains("ClassNameCompatSupport.resolveClass"));
    }
}
