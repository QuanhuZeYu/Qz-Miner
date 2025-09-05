package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class BaseChainViewer {
    public Logger LOG = LogManager.getLogger();

    public EntityPlayerMP playerMP;
    public Vector3i pos;
    public Block block;
    public int blockMeta;
    public TileEntity tileEntity;

    public Manager manager;
    public BasePositionFounder positionFounder;
    public LinkedBlockingQueue<Vector3i> canBreakPositions = new LinkedBlockingQueue<>();

    public BaseChainViewer(Vector3i pos, Manager manager) {
        this.pos = pos; this.playerMP = manager.player; this.manager = manager;
        this.block = playerMP.worldObj.getBlock(pos.x, pos.y, pos.z);
        this.blockMeta = playerMP.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        this.tileEntity = playerMP.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        this.positionFounder = new BasePositionFounder(pos, canBreakPositions, playerMP, 32, 6400);
        MyMod.parallelTick.addPreServerTickTask(this.positionFounder);

        this.registry();
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (!manager.inPressChainKey) {
            this.unRegistry();
        }
        if (canBreakPositions.isEmpty()) {
            return;
        }
        float particle = event.renderTickTime;
    }

    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
    }

    public void unRegistry() {
        FMLCommonHandler.instance().bus().unregister(this);
    }
}
