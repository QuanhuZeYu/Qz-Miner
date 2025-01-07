package club.heiqi.qz_miner.lootGame;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.network.PacketSweepMine;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.util.CheckCompatibility;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import org.joml.Vector3i;
import ru.timeconqueror.lootgames.api.block.GameBlock;
import ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock;
import ru.timeconqueror.lootgames.api.util.Pos2i;
import ru.timeconqueror.lootgames.common.block.tile.MSMasterTile;
import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;
import ru.timeconqueror.lootgames.minigame.minesweeper.MSBoard;
import ru.timeconqueror.lootgames.utils.future.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class FindMines {
    public static FindMines findMines = new FindMines();
    public static long coolDown = (long) (Config.coolDown * 1_000_000_000L - 2000000000L); // 冷却1分钟
    public long lastUse = 0;

    public boolean inReady = false; // 持续准备标志
    public long durationReady = 0;
    @SubscribeEvent
    public void findMines(TickEvent.PlayerTickEvent event) {
        if (!CheckCompatibility.isHasClass_MSMTile) {
            unregister();
            return;
        }
        if (event.side.isClient()) return;
        EntityPlayer player = event.player;
        ModeManager modeManager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (modeManager == null) return;
        if (modeManager.getIsReady()) {
            if (!inReady) { // 首次进入设置状态
                inReady = true;
                durationReady = System.nanoTime();
            } else { // 持续状态下进行计时
                durationReady = System.nanoTime() - durationReady;
                if (durationReady > 3000000000L) { // 持续按压了5s
                    if (System.nanoTime() - lastUse > coolDown) { // 上次使用时间到现在超过了冷却时间
                        //MY_LOG.LOG.info("准备扫描地雷");
                        findSMT_Mines(player);
                        lastUse = System.nanoTime();
                    }
                }
            }
        } else {
            inReady = false;
        }

    }

    public void findSMT_Mines(EntityPlayer player) {
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = player.worldObj.getBlock(playerPos.x + x, playerPos.y + y, playerPos.z + z);
                    if (block instanceof GameBlock) {
                        BlockPos master = SmartSubordinateBlock.getMasterPos(player.worldObj, new BlockPos(playerPos.x + x, playerPos.y + y, playerPos.z + z));
                        Vector3i masterPos = new Vector3i(master.getX(), master.getY(), master.getZ());
                        TileEntity SMT = player.worldObj.getTileEntity(masterPos.x, masterPos.y, masterPos.z);
                        if (SMT instanceof MSMasterTile MSMTile) {
                            findMines(player, MSMTile, masterPos);
                            return; // 找到一个就返回
                        }
                    }
                }
            }
        }
    }

    public void findMines(EntityPlayer player, MSMasterTile MSMTile, Vector3i blockPos) {
        GameMineSweeper game = MSMTile.getGame();
        if (game == null) {
            MY_LOG.LOG.warn("LootGame游戏实例为空");
            return;
        }
        MSBoard board = game.getBoard();
        if (board == null) {
            MY_LOG.LOG.warn("LootGame游戏面板为空");
            return;
        }
        Block curB = player.worldObj.getBlock(blockPos.x, blockPos.y, blockPos.z);
        Vector3i curPos = new Vector3i(blockPos.x, blockPos.y, blockPos.z);
        int length = 0;
        while (curB instanceof GameBlock) {
            length += 1;
            curPos.add(0, 0, 1);
            curB = player.worldObj.getBlock(curPos.x, curPos.y, curPos.z);
        }
        Vector3i fieldStart = new Vector3i(blockPos.x + (length - board.size())  / 2, blockPos.y, blockPos.z + (length - board.size()) / 2);
        Set<Vector3i> findBomb = new HashSet<>();
        for (int x = 0; x < board.size(); x++) {
            for (int z = 0; z < board.size(); z++) {
                Vector3i fieldPos = new Vector3i(fieldStart.x + x, fieldStart.y, fieldStart.z + z);
                Pos2i fieldPos2i = new Pos2i(x, z);
                String type;
                try {
                    type = board.getType(fieldPos2i).getId() == -1 ? "BOMB" : "EMPTY";
                } catch (Exception e) {
                    MY_LOG.LOG.warn("LootGame获取地雷失败");
                    return;
                }
                if (type.equals("BOMB")) {
                    findBomb.add(fieldPos);
                }
            }
        }
        if (!findBomb.isEmpty()) {
            ArrayList<Vector3i> mines = new ArrayList<>(findBomb);
            QzMinerNetWork.sendMessageToPlayer(new PacketSweepMine(mines), (EntityPlayerMP) player);
        }
    }

    public static void register() {
        FMLCommonHandler.instance().bus().register(findMines);
    }

    public static void unregister() {
        FMLCommonHandler.instance().bus().unregister(findMines);
    }
}
