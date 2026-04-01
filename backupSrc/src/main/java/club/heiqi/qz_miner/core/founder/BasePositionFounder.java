package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.BaseOperator;
import club.heiqi.qz_miner.core.MinerConfig;
import club.heiqi.qz_miner.thread.Pauseable;
import cpw.mods.fml.common.FMLCommonHandler;
import gregtech.common.blocks.BlockOresAbstract;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;

public class BasePositionFounder extends Pauseable {
    public Logger LOG = LogManager.getLogger();

    public Vector3i center;
    public EntityPlayer player;
    public MinerConfig minerConfig;
    /**已收集的可采集点 外部容器*/
    public LinkedBlockingQueue<Vector3i> positions;
    /**已收集的可采集点 内部容器*/
    public HashSet<Vector3i> foundedPositions = new HashSet<>();

    public int curCount = 0; // 包含初始加入的中心块
    // ========== 挖掘样本 ==========
    public final Block sampleBlock;
    public final int sampleBlockMeta;
    public final TileEntity sampleTileEntity;

    public BasePositionFounder(
            Vector3i center,
            LinkedBlockingQueue<Vector3i> results,
            EntityPlayer player,
            MinerConfig minerConfig
    ) {
        setName("无差别搜索器");
        BaseOperator.compatibilityCheck();

        this.center = center;
        this.player = player;
        this.positions = results;
        this.minerConfig = minerConfig;
        addResult(center);

        sampleBlock = player.worldObj.getBlock(center.x, center.y, center.z);
        sampleBlockMeta = player.worldObj.getBlockMetadata(center.x, center.y, center.z);
        sampleTileEntity = player.worldObj.getTileEntity(center.x, center.y, center.z);
    }

    @Override
    public void run1() {
        int curRadius = 1;
        while (curCount < minerConfig.blockLimit && curRadius <= minerConfig.bigRadius) {
            // LOG.info("当前半径: {} 当前块数: {}", curRadius, curCount);
            for (int x = center.x - curRadius; x <= center.x + curRadius; x++) {
                for (int y = center.y - curRadius; y <= center.y + curRadius; y++) {
                    for (int z = center.z - curRadius; z <= center.z + curRadius; z++) {
                        Vector3i pos = new Vector3i(x, y, z);
                        if (checkCanAdd(pos)) {
                            this.addResult(pos);
                        }
                        if (curCount >= minerConfig.blockLimit) {
                            return;
                        }
                        waitUntil();
                        if (Thread.currentThread().isInterrupted()) {
                            LOG.info("线程被中断");
                            return;
                        }
                    }
                }
            }
            curRadius++;
            if (curRadius > minerConfig.bigRadius) {
                break; // 超出半径范围，退出
            }
        }
    }

    public boolean checkCanAdd(Vector3i pos) {
        if (foundedPositions.contains(pos)) {
            // LOG.info("重复的点");
            return false;
        }
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        if (block.equals(Blocks.air) || block.getMaterial().isLiquid() || block.equals(Blocks.bedrock)) {
            return false;
        }
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);

        // 玩家脚下的一个方块不能被挖掘
        if (pos.x == playerPos.x && pos.y == (playerPos.y - 1) && pos.z == playerPos.z) {
            return false;
        }

        // 如果是创造模式全都能挖掘
        if (player.capabilities.isCreativeMode) return true;
        return block.canHarvestBlock(player, blockMeta);
    }

    public void addResult(Vector3i pos) {
        // LOG.info("添加位置: x: {} y: {} z: {}", pos.x, pos.y, pos.z);
        try {
            this.positions.put(pos);
            this.foundedPositions.add(pos);
            curCount++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 重新设置中断标志位
        }

        // 触发矿脉探索功能
        if (BaseOperator.hasVP_API && DeterminingIdentical.hasBlockBaseOre &&
                player.worldObj.isRemote && FMLCommonHandler.instance().getEffectiveSide().isClient() &&
                player.worldObj.getBlock(pos.x, pos.y, pos.z) instanceof BlockOresAbstract
        ) {
            player.worldObj.getBlock(pos.x, pos.y, pos.z).onBlockActivated(player.worldObj, pos.x, pos.y, pos.z, player, 0,0,0,0);
        }
    }
}
