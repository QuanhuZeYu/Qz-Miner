package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MOD_INFO;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

public enum MainModeEnum {
    ChainMode,
    RangeMode;

    public static List<String> getStringList() {
        List<String> stringList = new ArrayList<>();
        for(MainModeEnum mainModeEnum : values()) {
            stringList.add(mainModeEnum.toString());
        }
        return stringList;
    }

    public static List<String> getUnlocalizedStringList() {
        List<String> unlocalizedStringList = new ArrayList<>();
        for(String name: getStringList()) {
            String unlocalizedString = MOD_INFO.MODID+".mode."+name;
            unlocalizedStringList.add(unlocalizedString);
        }
        return unlocalizedStringList;
    }

    @SideOnly(Side.CLIENT)
    public static List<String> getLocalizationList() {
        List<String> localizationList = new ArrayList<>();
        for(String name: getUnlocalizedStringList()) {
            String localizationString = I18n.format(name);
        }
        return localizationList;
    }
}
