package club.heiqi.qz_miner.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import club.heiqi.qz_miner.Config;
import org.junit.Test;

/** 两代 GT 自然矿字段的 headless 兼容测试。 */
public class FortuneCompatHelperTest {

    /** 2.9 OreInfo 使用 isNatural 字段。 */
    @Test
    public void readsModernNaturalField() {
        Config.enableFortuneForPlacedOre = false;
        assertTrue(FortuneCompatHelper.shouldTreatOreAsNatural(new ModernOreInfo(true)));
        assertFalse(FortuneCompatHelper.shouldTreatOreAsNatural(new ModernOreInfo(false)));
    }

    /** 2.8 TileEntityOres 使用 mNatural 字段。 */
    @Test
    public void readsLegacyNaturalField() {
        Config.enableFortuneForPlacedOre = false;
        assertTrue(FortuneCompatHelper.shouldTreatOreAsNatural(new LegacyOreInfo(true)));
        assertFalse(FortuneCompatHelper.shouldTreatOreAsNatural(new LegacyOreInfo(false)));
    }

    /** 玩家放置矿配置对两代字段保持相同语义。 */
    @Test
    public void placedOreConfigurationOverridesBothGenerations() {
        Config.enableFortuneForPlacedOre = true;
        assertTrue(FortuneCompatHelper.shouldTreatOreAsNatural(new ModernOreInfo(false)));
        assertTrue(FortuneCompatHelper.shouldTreatOreAsNatural(new LegacyOreInfo(false)));
    }

    private static final class ModernOreInfo {
        private final boolean isNatural;
        private ModernOreInfo(boolean natural) { this.isNatural = natural; }
    }

    private static final class LegacyOreInfo {
        private final boolean mNatural;
        private LegacyOreInfo(boolean natural) { this.mNatural = natural; }
    }
}
