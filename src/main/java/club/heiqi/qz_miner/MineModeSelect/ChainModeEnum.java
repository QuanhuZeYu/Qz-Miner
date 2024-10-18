package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MOD_INFO;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

public enum ChainModeEnum {
    RectangularMode;

    public static List<String> getStringList() {
        List<String> stringList = new ArrayList<>();
        for (ChainModeEnum mode : ChainModeEnum.values()) {
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

    @SideOnly(Side.CLIENT)
    public static List<String> getLocalizationList() {
        List<String> localizationStringList = new ArrayList<String>();
        for(String unlocalizedString : getUnlocalizedStringList()) {
            String localizedString = I18n.format(unlocalizedString);
            localizationStringList.add(localizedString);
        }
        return localizationStringList;
    }
}
