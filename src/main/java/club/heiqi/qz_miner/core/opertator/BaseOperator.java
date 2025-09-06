package club.heiqi.qz_miner.core.opertator;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.Manager;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
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

public class BaseOperator {
    public Logger LOG = LogManager.getLogger();

    public EntityPlayerMP playerMP;
    public Vector3i pos;
    public Block block;
    public int blockMeta;
    public TileEntity tileEntity;

    public Manager manager;
    public BasePositionFounder positionFounder;
    public LinkedBlockingQueue<Vector3i> canBreakPositions = new LinkedBlockingQueue<>();

    public BaseOperator(Vector3i pos, Manager manager) {
        this.pos = pos; this.playerMP = manager.player; this.manager = manager;
        this.block = playerMP.worldObj.getBlock(pos.x, pos.y, pos.z);
        this.blockMeta = playerMP.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        this.tileEntity = playerMP.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        this.positionFounder = createPositionFounder();
        MyMod.parallelTick.addPreServerTickTask(this.positionFounder);

        this.registry();
    }
    public BasePositionFounder createPositionFounder() {
        return new BasePositionFounder(
                pos,
                canBreakPositions,
                playerMP,
                manager.pConfig
        );
    }

    @SubscribeEvent
    public void breakTask(TickEvent.ServerTickEvent event) {
        if (!manager.inPressChainKey) {
            this.unRegistry();
        }

        if (canBreakPositions.isEmpty()) {
            return;
        }

        int breakCountInTick = 0;
        Vector3i pos = canBreakPositions.poll();
        while (pos != null) {
            playerMP.theItemInWorldManager.tryHarvestBlock(pos.x, pos.y, pos.z);
            breakCountInTick++;
            if (breakCountInTick >= 64) {
                return;
            }

            pos = canBreakPositions.poll();
        }

        if (positionFounder.stopped.get()) {
            this.unRegistry();
        }
    }


    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
        LOG.info("连锁执行器注册成功");
    }
    public void unRegistry() {
        // new Thread(() -> {
        //     try {
        //         Thread.sleep(10);
        //     } catch (InterruptedException e) {
        //         Thread.currentThread().interrupt();
        //     }
            FMLCommonHandler.instance().bus().unregister(this);
            manager.inChain = false;
            LOG.info("连锁执行器注销成功");
        // }).start();
    }
}
