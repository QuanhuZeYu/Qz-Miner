package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MOD_INFO;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.CenterMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.CenterRectangularMode;
import club.heiqi.qz_miner.MineModeSelect.AllRangeMode.PlanarRestrictedMode;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

public enum RangeModeEnum {
    centerMode,
    planarRestrictedMode,
    centerRectangularMode;

    public static List<AbstractMiner> getMinerChain() {
        List<AbstractMiner> miner = new ArrayList<>();
        miner.add(new CenterMode());
        miner.add(new PlanarRestrictedMode());
        miner.add(new CenterRectangularMode());
        return miner;
    }

    public static List<String> getStringList() {
        List<String> stringList = new ArrayList<>();
        for (RangeModeEnum mode : RangeModeEnum.values()) {
            stringList.add(mode.name());
        }
        return stringList;
    }

    public static List<String> getUnlocalizedStringList() {
        List<String> allStrings = new ArrayList<String>();
        for (String mode : getStringList()) {
            String unlocalizedString = MOD_INFO.MODID+".mode."+mode;
            allStrings.add(unlocalizedString);
        }
        return allStrings;
    }

    public static RangeModeEnum nextMode(RangeModeEnum currentMode) {
        int index = currentMode.ordinal();
        index++;
        if (index >= RangeModeEnum.values().length) {
            index = 0;
        }
        return RangeModeEnum.values()[index];
    }

    public static RangeModeEnum getMode(int mode) {
        return RangeModeEnum.values()[mode % RangeModeEnum.values().length];
    }

    @SideOnly(Side.CLIENT)
    public static List<String> getLocalizationList() {
        List<String> localizationList = new ArrayList<>();
        for(String unLocalizedName : getStringList()) {
            unLocalizedName = MOD_INFO.MODID+".mode."+unLocalizedName;
            localizationList.add(I18n.format(unLocalizedName));
        }
        return localizationList;
    }
}
