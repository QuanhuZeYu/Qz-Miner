package club.heiqi.qz_miner.client;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** 防止渲染/输入旁路及 private API 接入；HUD 只消费公开 UILib 声明。 */
public class HudArchitectureBoundaryTest {

    private static final String[] FORBIDDEN_RENDERING_TYPES = {
            "net/minecraftforge/client/event/RenderGameOverlayEvent",
            "net/minecraft/client/gui/FontRenderer",
    };

    @Test
    public void productionSourcesContainNoLegacyHudRendering() throws Exception {
        List<File> classes = productionClasses();
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + classes.size(), classes.size() > 50);
        List<String> offenders = new ArrayList<String>();
        for (File classFile : classes) {
            Set<String> refs = CompiledClasses.classRefs(classFile);
            for (String forbidden : FORBIDDEN_RENDERING_TYPES) {
                for (String ref : refs) {
                    if (ref.equals(forbidden) || ref.startsWith(forbidden + "$")) {
                        offenders.add(CompiledClasses.relative(classFile) + " -> " + ref);
                    }
                }
            }
        }
        Assert.assertEquals("Miner 不得链接原版 HUD 渲染类型（自绘 HUD 回归）",
                Collections.<String>emptyList(), offenders);
    }

    @Test
    public void productionSourcesUseOnlyPublicUiLibApi() throws Exception {
        List<File> classes = productionClasses();
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + classes.size(), classes.size() > 50);
        List<String> offenders = new ArrayList<String>();
        for (File classFile : classes) {
            if (CompiledClasses.refs(classFile).hasClassRefUnder("club/heiqi/uilib/internal")) {
                offenders.add(CompiledClasses.relative(classFile) + " -> UILib 内部实现包");
            }
        }
        Assert.assertEquals(Collections.<String>emptyList(), offenders);
    }

    private static List<File> productionClasses() {
        List<File> classes = new ArrayList<File>();
        for (File classFile : CompiledClasses.classFiles()) {
            if (CompiledClasses.relative(classFile).startsWith("club/heiqi/qz_miner/")) {
                classes.add(classFile);
            }
        }
        return classes;
    }
}
