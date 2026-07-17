package club.heiqi.qz_miner.compat.adapter.gregtech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/** GT 反射能力档案的 headless 边界测试。 */
public class GregTechCableCompatAdapterTest {

    /** GT 核心类型不存在属于正常未安装，不产生降级 WARN。 */
    @Test
    public void missingGregTechCoreIsNormalAbsence() {
        CountingDiagnostic diagnostic = new CountingDiagnostic();
        GregTechCableCompatAdapter.CapabilityProfile profile =
            GregTechCableCompatAdapter.CapabilityProfile.resolve(name -> null, diagnostic);
        assertFalse(new GregTechCableCompatAdapter(profile).isAvailable());
        assertEquals("NO_GT", profile.selectedProfile);
        assertTrue(profile.missingCapabilities.isEmpty());
        assertEquals(0, diagnostic.warningCount);
    }

    /** GT 核心存在但必需类型缺失时，初始化只汇总告警一次。 */
    @Test
    public void missingRequiredTypesWarnOnceDuringInitialization() {
        CountingDiagnostic diagnostic = new CountingDiagnostic();
        GregTechCableCompatAdapter.CapabilityProfile profile = GregTechCableCompatAdapter.CapabilityProfile.resolve(
            name -> name.equals("gregtech.api.GregTechAPI") ? Object.class : null, diagnostic);
        assertFalse(profile.complete);
        assertEquals("GT_REFLECTION_DEGRADED", profile.selectedProfile);
        assertFalse(profile.missingCapabilities.isEmpty());
        assertEquals(1, diagnostic.warningCount);
        assertEquals(profile.missingCapabilities, diagnostic.missingCapabilities);
    }

    /** 类型可解析但 descriptor 不匹配时仍分类为 GT 签名降级，缺失列表不可变。 */
    @Test(expected = UnsupportedOperationException.class)
    public void missingMembersAreReportedByImmutableCapabilityList() {
        CountingDiagnostic diagnostic = new CountingDiagnostic();
        GregTechCableCompatAdapter.CapabilityProfile profile = GregTechCableCompatAdapter.CapabilityProfile.resolve(
            name -> Object.class, diagnostic);
        assertEquals(1, diagnostic.warningCount);
        assertTrue(profile.missingCapabilities.get(0).startsWith("method:"));
        profile.missingCapabilities.add("unexpected");
    }

    /** 适配器源码不得恢复 GT 静态链接或仅新版本客户端刷新入口。 */
    @Test
    public void sourceKeepsOptionalDependencyBoundary() throws Exception {
        Path source = Paths.get("src/main/java/club/heiqi/qz_miner/compat/adapter/gregtech/GregTechCableCompatAdapter.java");
        String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(content.contains("import gregtech."));
        assertFalse(content.contains("getMethods("));
        assertFalse(content.contains("getFields("));
        assertFalse(content.contains("issueClientUpdate"));
        assertTrue(content.contains("ClassNameCompatSupport.resolveClass"));
    }

    private static final class CountingDiagnostic implements GregTechCableCompatAdapter.ProfileDiagnostic {
        int warningCount;
        List<String> missingCapabilities;

        @Override
        public void degraded(String selectedProfile, List<String> missingCapabilities) {
            warningCount++;
            this.missingCapabilities = missingCapabilities;
        }
    }
}
