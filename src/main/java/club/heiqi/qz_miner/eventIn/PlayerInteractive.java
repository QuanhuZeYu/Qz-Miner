package club.heiqi.qz_miner.eventIn;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.utils.Utils;
import club.heiqi.qz_miner.util.CalculateSightFront;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class PlayerInteractive {
    public static PlayerInteractive INSTANCE = new PlayerInteractive();

    @SubscribeEvent
    public void onPlayerInteractEvent(PlayerInteractEvent event) {
        EntityPlayer player = event.entityPlayer;
        // ModeManager的空值处理
        ModeManager modeManager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (modeManager == null) return;
        if (!modeManager.getIsReady()) return;
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && !player.isSneaking()) {
            Block block = player.worldObj.getBlock(event.x, event.y, event.z);
            // 不是农作物提前返回
            if (!(block instanceof BlockCrops)) return;
            if (event.world.getBlockMetadata(event.x, event.y, event.z) < 7) return;
            player.swingItem();
            if (event.world.isRemote) return;
            Vector3f dropPos = Utils.getItemDropPos(player);
            List<Vector3i> crops = findCrops(event.world, new Vector3i(event.x, event.y, event.z));
            crops.forEach(cropPos -> {
                int metadata = event.world.getBlockMetadata(cropPos.x, cropPos.y, cropPos.z);
                if (metadata < 7) return;
                Block curBlock = event.world.getBlock(cropPos.x, cropPos.y, cropPos.z);
                List<ItemStack> drops1 = curBlock.getDrops(event.world, cropPos.x, cropPos.y, cropPos.z, metadata, 0);

                try {
                    event.world.setBlockMetadataWithNotify(cropPos.x, cropPos.y, cropPos.z, 0, 3);
                } catch (Exception e) {
                    // 处理设置方块元数据时可能出现的异常
                    MY_LOG.LOG.error("Error setting block metadata at position {}: {}", cropPos, e.getMessage());
                    return;
                }

                drops1.forEach(itemStack -> {
                    if (itemStack == null) {
                        MY_LOG.LOG.error("Null itemStack found at position {}", cropPos);
                        return;
                    }
                    EntityItem entityItem = new EntityItem(event.world, dropPos.x, dropPos.y, dropPos.z, itemStack);

                    try {
                        event.world.spawnEntityInWorld(entityItem);
                    } catch (Exception e) {
                        // 处理生成掉落物实体时可能出现的异常
                        MY_LOG.LOG.error("Error spawning entityItem at position {}: {}", cropPos, e.getMessage());
                    }
                });
            });
        }
    }

    /**
     *
     * @param world 当前世界
     * @param pos 当前位置
     * @return 所有农作物的位置
     */
    public List<Vector3i> findCrops(World world, Vector3i pos) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }

        List<Vector3i> result = new ArrayList<>();
        Set<Vector3i> visited = new HashSet<>();
        Queue<Vector3i> nextChain = new LinkedList<>();
        nextChain.add(pos);

        while (!nextChain.isEmpty()) {
            Vector3i vec = nextChain.poll();
            if (vec == null || visited.contains(vec)) {
                continue;
            }
            visited.add(vec);

            try {
                Block block = world.getBlock(vec.x, vec.y, vec.z);
                int meta = world.getBlockMetadata(vec.x, vec.y, vec.z);

                if (!(block instanceof BlockCrops)) {
                    continue;
                }
                if (meta < 7) {
                    continue;
                }
                result.add(vec);
                nextChain.addAll(findNear(vec));
            } catch (Exception e) {
                // 处理可能出现的异常，例如坐标超出范围
                System.err.println("Error finding crops at position " + vec + ": " + e.getMessage());
            }
        }
        return result;
    }



    public List<Vector3i> findNear(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        if (pos.y + 1 < 256) {
            result.add(new Vector3i(pos.x, pos.y + 1, pos.z));
        }
        if (pos.y + 1 < 256) {
            result.add(new Vector3i(pos.x, pos.z + 1, pos.y));
        }
        result.add(new Vector3i(pos.x + 1, pos.y, pos.z));
        result.add(new Vector3i(pos.x - 1, pos.y, pos.z));
        result.add(new Vector3i(pos.x, pos.y, pos.z + 1));
        result.add(new Vector3i(pos.x, pos.y, pos.z - 1));
        return result;
    }

    public void register(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(PlayerInteractive.INSTANCE);
    }
}
