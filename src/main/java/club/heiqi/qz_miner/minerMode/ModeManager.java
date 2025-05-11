package club.heiqi.qz_miner.minerMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerMode.enums.ChainMode;
import club.heiqi.qz_miner.minerMode.enums.MainMode;
import club.heiqi.qz_miner.minerMode.enums.RangeMode;
import club.heiqi.qz_miner.minerMode.enums.Sides;
import club.heiqi.qz_miner.minerMode.utils.Utils;
import club.heiqi.qz_miner.network.PacketChainMode;
import club.heiqi.qz_miner.network.PacketMainMode;
import club.heiqi.qz_miner.network.PacketRangeMode;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个玩家都有独属于自身的管理类
 */
public class ModeManager {
    public static List<ModeManager> MANAGERS = new ArrayList<>();

    public Logger LOG = LogManager.getLogger();
    public UUID registryInfo = UUID.randomUUID();
    /**掉落物管理*/
    // 全局掉落物表：Key=位置，Value=该位置的实体队列（线程安全）
    public static ConcurrentHashMap<Vector3i, ConcurrentLinkedQueue<EntityItem>> GLOBAL_DROPS = new ConcurrentHashMap<>();
    public ConcurrentLinkedQueue<Vector3i> selfDrops = new ConcurrentLinkedQueue<>();

    /**缓存的玩家引用*/
    public EntityPlayer player;
    public World world;

    /**模式枚举 - 通过网络发包修改值*/
    public MainMode mainMode = MainMode.CHAIN_MODE; // 默认为范围模式
    public RangeMode rangeMode = RangeMode.RECTANGULAR; // 默认为矩形模式
    public ChainMode chainMode = ChainMode.STRICT; // 默认为严格模式

    /**当前模式 - 在触发模式之后动态创建实例 - 常态保持为null*/
    @Nullable
    public AbstractMode curMode;
    @Nullable
    public AbstractMode clientMode;

    public AtomicBoolean isReady = new AtomicBoolean(false);
    public AtomicBoolean isRunning = new AtomicBoolean(false);
    public AtomicBoolean printResult = new AtomicBoolean(true);

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param center 方块所在的坐标
     */
    public void proxyMine(Vector3i center) {
        switch (mainMode) {
            case CHAIN_MODE -> {
                curMode = chainMode.newAbstractMode(this, center, Sides.SERVER);
                curMode.mineModeAutoSetup();
            }
            case RANGE_MODE -> {
                curMode = rangeMode.newAbstractMode(this, center, Sides.SERVER);
                curMode.mineModeAutoSetup();
            }
        }
        isRunning.set(true);
    }
    public List<Vector3i> renderCache = new ArrayList<>();
    @SideOnly(Side.CLIENT)
    public void proxyRender(Vector3i center) {
        switch (mainMode) {
            case CHAIN_MODE -> {
                clientMode = chainMode.newAbstractMode(this, center, Sides.CLIENT);
                clientMode.renderModeAutoSetup();
            }
            case RANGE_MODE -> {
                clientMode = rangeMode.newAbstractMode(this, center, Sides.CLIENT);
                clientMode.renderModeAutoSetup();
            }
        }
    }
    public void proxyInteract(Vector3i center) {
        switch (mainMode) {
            case CHAIN_MODE -> {
                curMode = chainMode.newAbstractMode(this, center, Sides.SERVER);
                curMode.interactModeAutoSetup();
            }
            case RANGE_MODE -> {
                curMode = rangeMode.newAbstractMode(this, center, Sides.SERVER);
                curMode.interactModeAutoSetup();
            }
        }
        isRunning.set(true);
    }

    public void nextMainMode() {
        mainMode = MainMode.values()[(mainMode.ordinal() + 1) % MainMode.values().length];
        QzMinerNetWork.sendMessageToServer(new PacketMainMode(mainMode));
    }

    public void nextSubMode() {
        switch (mainMode) {
            case CHAIN_MODE -> {
                chainMode = ChainMode.values()[(chainMode.ordinal() + 1) % ChainMode.values().length];
                QzMinerNetWork.sendMessageToServer(new PacketChainMode(chainMode));
            }
            case RANGE_MODE -> {
                rangeMode = RangeMode.values()[(rangeMode.ordinal() + 1) % RangeMode.values().length];
                QzMinerNetWork.sendMessageToServer(new PacketRangeMode(rangeMode));
            }
        }
    }

    public void setIsReady(boolean isReady) {
        this.isReady.set(isReady);
    }

    public boolean getIsReady() {
        return isReady.get();
    }

    public void setPrintResult(boolean printResult) {
        this.printResult.set(printResult);
    }

    public boolean getPrintResult() {
        return printResult.get();
    }


    // ==================== 事件订阅 - 监听玩家自身即可，简化触发流程
    public boolean register() {
        // 1.检查全局中是否有相同玩家的管理器
        for (ModeManager manager : MANAGERS) {
            if (manager.player.getUniqueID().equals(getPlayer().getUniqueID())) {
                return false;
            }
        }
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        EntityPlayer player = getPlayer();
        LOG.info("{} 管理器:{} 注册完成", player.getDisplayName(), registryInfo);
        MANAGERS.add(this);
        return true;
    }
    public void unregister() {
        if (!captureDrops.isEmpty()) dropCapture();
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        EntityPlayer player = getPlayer();
        LOG.info("{} 管理器:{} 卸载完毕", player.getDisplayName(), registryInfo);
        MANAGERS.remove(this);
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }

    /**
     * 连锁破坏事件入口
     */
    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent event) {
        // 判断是否是自己挖的
        if (!event.getPlayer().getUniqueID().equals(this.player.getUniqueID())) {
            //LOG.info("非自身挖掘");
            return;
        }
        // 刷新世界引用
        updateWP(event.world,event.getPlayer());

        selfDrops.add(new Vector3i(event.x, event.y, event.z));
        if (isRunning.get()) {
            //LOG.info("已在运行，退出");
            return;
        }
        if (!isReady.get()) {
            //LOG.info("未准备，退出");
            return;
        }

        // 连锁触发
        Vector3i breakBlockPos = new Vector3i(event.x, event.y, event.z);
        try {
            /*LOG.info("[挖掘] 设置代理挖掘任务");*/
            event.setCanceled(true);
            proxyMine(breakBlockPos);
        } catch (Exception e) {
            LOG.info("代理挖掘时发生错误![An error occurred while proxy mining]");
            LOG.info(e);
        }
    }

    /**
     * 连锁右键事件入口
     */
    @SubscribeEvent
    public void interactEvent(PlayerInteractEvent event) {
        EntityPlayer player = event.entityPlayer;
        // 确保触发者是管理器玩 触发在服务端
        if (!player.getUniqueID().equals(this.player.getUniqueID()) || event.world.isRemote) return;
        // 刷新存储状态
        this.world = event.world;
        this.player = player;

        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (!getIsReady()) return;
        if (isRunning.get()) return;

        Vector3i interactPos = new Vector3i(event.x, event.y, event.z);
        try {
            proxyInteract(interactPos);
        }
        catch (Exception e) {
            LOG.info("代理交互时发生错误!");
            LOG.info(e);
        }
    }

    /**
     * 掉落容器
     */
    List<ItemStack> captureDrops = new ArrayList<>();
    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        EntityPlayer harvester = event.harvester;
        // 确保采集者是管理器管理的玩家 - 通过UUID判断
        if (harvester == null || harvester.getUniqueID() != player.getUniqueID()) return;
        // 更新玩家
        player = harvester;
        // 更新世界
        if (!world.isRemote) world = event.world;
        if (!getIsReady()) return;
        if (Config.dropItemToSelf) { // 如果配置打开了掉落到自己附近
            {
                if (captureDrops.isEmpty()) {
                    captureDrops.addAll(event.drops);
                    event.drops.clear();
                }
                else {
                    /*for (ItemStack captureDrop : captureDrops) { // 遍历捕捉容器
                        for (ItemStack drop : new ArrayList<>(event.drops)) { // 遍历凋落物
                            if (Utils.areStacksMergeable(captureDrop, drop)) { // 如果遇到可以合并的
                                captureDrop.stackSize += drop.stackSize; // 增加堆叠数量
                                event.drops.remove(drop); // 从掉落事件列表中移除添加过的
                            }
                        }
                    }*/
                    // 修改为线程安全遍历模式
                    int i = 0;
                    while (i < captureDrops.size()) {
                        ItemStack captureDrop = captureDrops.get(i);
                        for (ItemStack drop : new ArrayList<>(event.drops)) {
                            if (Utils.areStacksMergeable(captureDrop, drop)) {
                                captureDrop.stackSize += drop.stackSize;
                                event.drops.remove(drop);
                            }
                        }
                        i++;
                    }
                }
            }
            // 如果经过合并后依然还有，直接添加到捕获列表中
            if (!event.drops.isEmpty()) captureDrops.addAll(event.drops);
            event.drops.clear();
        }
    }

    public long updateTime = System.currentTimeMillis();
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (System.currentTimeMillis() - updateTime >= 1_000) {
            for (EntityPlayerMP playerMP : new ArrayList<>(FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList)) {
                if (playerMP.getUniqueID().equals(this.player.getUniqueID())) {
                    updateWP(playerMP.worldObj,playerMP);
                    break;
                }
            }
            updateTime = System.currentTimeMillis();
        }
        if (getIsReady()) return;

        if (!captureDrops.isEmpty()) {
            Vector3f dropPos = Utils.getItemDropPos(player);
            for (ItemStack drop : captureDrops) {
                EntityItem entityDrop = new EntityItem(
                        world,
                        dropPos.x,
                        dropPos.y,
                        dropPos.z,
                        drop
                );
                entityDrop.delayBeforeCanPickup = 5;
                world.spawnEntityInWorld(entityDrop);
            }
            captureDrops.clear();
        }
    }

    public void dropCapture() {
        List<ItemStack> drops = new ArrayList<>(captureDrops);
        captureDrops.clear();
        Vector3f dropPos = Utils.getItemDropPos(player);
        drops.forEach(drop -> {
            EntityItem entityDrop = new EntityItem(
                world,
                dropPos.x,
                dropPos.y,
                dropPos.z,
                drop
            );
            entityDrop.delayBeforeCanPickup = 5;
            world.spawnEntityInWorld(entityDrop);
        });
    }

    public void updateWP(World world, EntityPlayer player) {
        if (player.getUniqueID().equals(this.player.getUniqueID())) {
            this.player = player; this.world = world;
        }
    }
}
