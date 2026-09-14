package club.heiqi.qz_miner.compat.adapter.gregtech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** GT 反射能力档案的 headless 边界测试。 */
public class GregTechCableCompatAdapterTest {

    private static final String ADAPTER_INTERNAL_NAME =
            "club/heiqi/qz_miner/compat/adapter/gregtech/GregTechCableCompatAdapter";

    /** 完整档案必须探测的成员能力数量：collectMissingMembers 的探测项总数。 */
    private static final int PROBED_CAPABILITY_COUNT = 18;

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

    /**
     * 类型可解析但 descriptor 不匹配时仍分类为 GT 签名降级，缺失列表不可变。
     *
     * <p>缺失能力清单只断言<b>结构性质</b>（探测总数、无重复、类别前缀、诊断与档案同一份清单），
     * 不断言 method:/field: 标签字面量——那些标签是实现里的文案，改文案会误报、
     * 而清单塌缩成空这类真回归由数量与结构性质直接拦住。</p>
     */
    @Test(expected = UnsupportedOperationException.class)
    public void missingMembersAreReportedByImmutableCapabilityList() {
        CountingDiagnostic diagnostic = new CountingDiagnostic();
        GregTechCableCompatAdapter.CapabilityProfile profile = GregTechCableCompatAdapter.CapabilityProfile.resolve(
            name -> Object.class, diagnostic);
        assertEquals(1, diagnostic.warningCount);
        assertTrue(profile.missingCapabilities.get(0).startsWith("method:"));
        assertEquals("完整档案必须逐项上报固定数量的成员能力（漏探测=防线变弱，多探测=新增必需能力）",
            PROBED_CAPABILITY_COUNT, profile.missingCapabilities.size());
        assertEquals("缺失能力描述符不得重复",
            new HashSet<String>(profile.missingCapabilities).size(), profile.missingCapabilities.size());
        int categorized = 0;
        for (String capability : profile.missingCapabilities) {
            if (capability.startsWith("method:") || capability.startsWith("field:")
                    || capability.startsWith("type:")) {
                categorized++;
            }
        }
        assertEquals("每项缺失能力都必须带 method:/field:/type: 类别前缀",
            profile.missingCapabilities.size(), categorized);
        assertSame("降级诊断必须拿到被测档案的同一份清单",
            profile.missingCapabilities, diagnostic.missingCapabilities);
        profile.missingCapabilities.add("unexpected");
    }

    /** 适配器编译产物不得恢复 GT 静态链接，也不得扫描公开成员。 */
    @Test
    public void sourceKeepsOptionalDependencyBoundary() throws Exception {
        // 类解析/成员查找发生在嵌套类里，而嵌套类有各自独立的常量池，因此必须合并整族产物再判定
        CompiledClasses.Refs refs = familyRefs(ADAPTER_INTERNAL_NAME);
        assertFalse("不得静态链接 GT（常量池不得出现 gregtech/ 类型）: " + refs.classRefs,
            refs.hasClassRefUnder("gregtech/"));
        assertFalse("不得扫描公开成员：Class.getMethods",
            refs.methodRefs.contains("java/lang/Class#getMethods"));
        assertFalse("不得扫描公开字段：Class.getFields",
            refs.methodRefs.contains("java/lang/Class#getFields"));
        assertTrue("可选类型解析必须经 ClassNameCompatSupport.resolveClass",
            refs.methodRefs.contains("club/heiqi/qz_miner/compat/adapter/ClassNameCompatSupport#resolveClass"));
    }

    /** 该类型与其全部嵌套类的常量池引用并集（嵌套类不共享外层常量池）。 */
    private static CompiledClasses.Refs familyRefs(String ownerInternalName) throws Exception {
        CompiledClasses.Refs merged = new CompiledClasses.Refs();
        for (java.io.File classFile : CompiledClasses.classFiles()) {
            String relative = CompiledClasses.relative(classFile);
            if (!relative.equals(ownerInternalName + ".class")
                    && !relative.startsWith(ownerInternalName + "$")) {
                continue;
            }
            CompiledClasses.Refs refs = CompiledClasses.refs(classFile);
            merged.classRefs.addAll(refs.classRefs);
            merged.methodRefs.addAll(refs.methodRefs);
            merged.fieldRefs.addAll(refs.fieldRefs);
            merged.strings.addAll(refs.strings);
        }
        return merged;
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
