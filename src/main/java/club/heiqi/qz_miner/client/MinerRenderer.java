package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.core.BaseChainViewer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

public class MinerRenderer {
    public Logger LOG = LogManager.getLogger();
    public boolean inPressChainKey = false;

    public BaseChainViewer viewer;


    public Vector3i lastTarget = new Vector3i(Integer.MIN_VALUE);
    @SubscribeEvent
    public void onBlockHighLight(DrawBlockHighlightEvent event) {
        // 如果没有按下连锁键，不执行逻辑
        if (!inPressChainKey) {
            onNotPressChainKey();
            return;
        }

        // 检查看向的目标是否变更
        Vector3i target = new Vector3i(event.target.blockX, event.target.blockY, event.target.blockZ);
        if (!lastTarget.equals(target)) {
            lastTarget = target;
            onPressButChangeTarget();
        }
        previewRender();
    }

    private void previewRender() {
        if (viewer == null) {
            viewer = new BaseChainViewer(lastTarget);
        }
    }

    private void onPressButChangeTarget() {
        if (viewer != null) {
            viewer.unRegistry();

            viewer = new BaseChainViewer(lastTarget);
        }
    }

    private void onNotPressChainKey() {
        if (viewer != null) {
            viewer.inPressChainKey = false;
            viewer.unRegistry();

            viewer = null;
        }
    }


    public void registry() {
        // FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void unRegistry() {
        // FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
    }


}
