package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.MOD_INFO;
import club.heiqi.qz_miner.MineModeSelect.AllChainMode.EnhanceChainMode;
import club.heiqi.qz_miner.MineModeSelect.AllChainMode.ExcludeCrushedOreChainMode;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum ChainModeEnum {
    rectangularMode("rectangularMode", new EnhanceChainMode()),
    excludeCrushedOreMode("excludeCrushedOreMode", new ExcludeCrushedOreChainMode());

    public final String name;
    public final AbstractMiner miner;

    ChainModeEnum(String name0, AbstractMiner miner0) {
        this.name = name0;
        this.miner = miner0;
    }

    public static List<AbstractMiner> getMinerChain() {
        return Arrays.asList(rectangularMode.miner, excludeCrushedOreMode.miner);
    }

    public static List<String> getStringList() {
        return Arrays.asList(rectangularMode.name, excludeCrushedOreMode.name);
    }

    public static List<String> getUnlocalizedStringList() {
        List<String> allStrings = new ArrayList<String>();
        for (String mode : getStringList()) {
            String unlocalizedString = MOD_INFO.MODID+".mode."+mode;
            allStrings.add(unlocalizedString);
        }
        return allStrings;
    }

    public static ChainModeEnum nextMode(ChainModeEnum currentMode) {
        return ChainModeEnum.values()[(currentMode.ordinal() + 1) % ChainModeEnum.values().length];
    }

    public static ChainModeEnum getMode(int mode) {
        return ChainModeEnum.values()[mode % ChainModeEnum.values().length];
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
