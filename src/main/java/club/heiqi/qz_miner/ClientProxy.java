package club.heiqi.qz_miner;

import club.heiqi.qz_miner.client.keybind.KeyBind;
import club.heiqi.qz_miner.client.lootGame.RenderMines;
import club.heiqi.qz_miner.client.renderSelect.RenderSelect;
import club.heiqi.qz_miner.util.CheckCompatibility;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {
    public KeyBind keyBind;
    public RenderSelect renderSelect;

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        keyBind = new KeyBind();
        keyBind.register();

        renderSelect = new RenderSelect();
        RenderSelect.register(renderSelect);


    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        if (CheckCompatibility.isHasClass_MSMTile) {
            RenderMines renderMines = new RenderMines();
            renderMines.register();
        }
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);

    }
}
