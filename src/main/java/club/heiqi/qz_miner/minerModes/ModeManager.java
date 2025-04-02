package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.chainMode.BaseChainMode;
import club.heiqi.qz_miner.minerModes.chainMode.LumberJackMode;
import club.heiqi.qz_miner.minerModes.chainMode.RelaxChainMode;
import club.heiqi.qz_miner.minerModes.chainMode.StrictChainMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMineralMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMode;
import club.heiqi.qz_miner.minerModes.rangeMode.SphereMode;
import club.heiqi.qz_miner.minerModes.rangeMode.TunnelMode;
import club.heiqi.qz_miner.minerModes.utils.Utils;
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
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    /**当前模式*/
    public AbstractMode curMode;

    public volatile AtomicBoolean isRunning = new AtomicBoolean(false);
    public volatile AtomicBoolean isReady = new AtomicBoolean(false);
    public volatile AtomicBoolean printResult = new AtomicBoolean(true);

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param center 方块所在的坐标
     */
    public void proxyMine(Vector3i center) {
        switch (mainMode) {
            case CHAIN_MODE -> {
                curMode = chainMode.newAbstractMode(this, center);
                curMode.mineModeAutoSetup();
            }
            case RANGE_MODE -> {
                curMode = rangeMode.newAbstractMode(this, center);
                curMode.mineModeAutoSetup();
            }
        }
        LOG.info("[挖掘] 执行挖掘任务");
        isRunning.set(true);
    }
    public List<Vector3i> renderCache = new ArrayList<>();
    @SideOnly(Side.CLIENT)
    public void proxyRender(Vector3i center) {
        switch (mainMode) {
            case CHAIN_MODE -> {
                curMode = chainMode.newAbstractMode(this, center);
                curMode.renderModeAutoSetup();
            }
            case RANGE_MODE -> {
                curMode = rangeMode.newAbstractMode(this, center);
                curMode.renderModeAutoSetup();
            }
        }
    }
    public void proxyHarvestCrop(Vector3i center) {

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



    // 主模式枚举类，该枚举中包含所有模式
    public enum MainMode{
        CHAIN_MODE("qz_miner.chain_mode", ChainMode.getModes()),
        RANGE_MODE("qz_miner.range_mode", RangeMode.getModes()),
        ;
        public final String unLocalizedName;
        public final List<String> modes;

        MainMode(String unLocalizedName, List<String> modes) {
            this.unLocalizedName = unLocalizedName;
            this.modes = modes;
        }
    }
    // ========== 连锁模式
    public enum ChainMode {
        BASE_CHAIN_MODE("qz_miner.chainmode.base_chain"),
        STRICT("qz_miner.chainmode.strict"),
        LUMBER_JACK("qz_miner.chainmode.lumberjack"),
        RELAX("qz_miner.chainmode.relax"),
        ;
        public final String unLocalizedName;

        ChainMode(String unLocalizedName) {
            this.unLocalizedName = unLocalizedName;
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (ChainMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public AbstractMode newAbstractMode(ModeManager manager, Vector3i center) {
            switch (this) {
                case BASE_CHAIN_MODE -> {
                    return new BaseChainMode(manager, center);
                }
                case STRICT -> {
                    return new StrictChainMode(manager, center);
                }
                case LUMBER_JACK -> {
                    return new LumberJackMode(manager, center);
                }
                case RELAX -> {
                    return new RelaxChainMode(manager, center);
                }
                default -> {
                    return new BaseChainMode(manager, center);
                }
            }
        }
    }
    // ========== 范围模式
    public enum RangeMode {
        RECTANGULAR("qz_miner.rangemode.rectangular"),
        RECTANGULAR_MINERAL("qz_miner.rangemode.rectangular_mineral"),
        SPHERE("qz_miner.rangemode.sphere"),
        TUNNEL("qz_miner.rangemode.tunnel"),
        ;
        public final String unLocalizedName;

        RangeMode(String unLocalizedName) {
            this.unLocalizedName = unLocalizedName;
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (RangeMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public AbstractMode newAbstractMode(ModeManager manager, Vector3i center) {
            switch (this) {
                case RECTANGULAR -> {
                    return new RectangularMode(manager, center);
                }
                case RECTANGULAR_MINERAL ->  {
                    return new RectangularMineralMode(manager, center);
                }
                case SPHERE -> {
                    return new SphereMode(manager, center);
                }
                case TUNNEL -> {
                    return new TunnelMode(manager, center);
                }
                default -> {
                    return new RectangularMode(manager, center);
                }
            }
        }
    }



    // ==================== 事件订阅 - 监听玩家自身即可，简化触发流程
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        EntityPlayer player = getPlayer();
        LOG.info("{} 管理器:{} 注册完成", player.getDisplayName(), registryInfo);
    }
    public void unregister() {
        if (!captureDrops.isEmpty()) dropCapture();
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        EntityPlayer player = getPlayer();
        LOG.info("{} 管理器:{} 卸载完毕", player.getDisplayName(), registryInfo);
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }

    /**
     * 订阅方块破坏事件
     * @param event
     */
    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) return;
        EntityPlayer player = event.getPlayer();
        EntityPlayer thisPlayer = getPlayer();
        // 判断是否是自己挖的
        if (!player.getUniqueID().equals(thisPlayer.getUniqueID())) return;
        // 是自己的挖掘事件
        // 刷新存储状态
        this.world = event.world;
        this.player = player;

        selfDrops.add(new Vector3i(event.x, event.y, event.z));
        if (isRunning.get()) {
            /*LOG.info("[挖掘] 已在运行，退出");*/
            return;
        }
        if (!isReady.get()) {
            /*LOG.info("[挖掘] 未准备，退出");*/
            return;
        }

        // 连锁触发
        Vector3i breakBlockPos = new Vector3i(event.x, event.y, event.z);
        try {
            /*LOG.info("[挖掘] 设置代理挖掘任务");*/
            proxyMine(breakBlockPos);
        } catch (Exception e) {
            LOG.info("代理挖掘时发生错误![An error occurred while proxy mining]");
            LOG.info(e);
        }
    }

    List<ItemStack> captureDrops = new ArrayList<>();
    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        EntityPlayer harvester = event.harvester;
        if (harvester == null || harvester.getUniqueID() != player.getUniqueID()) return;
        world = event.world;
        if (!getIsReady()) return;
        if (Config.dropItemToSelf) {
            captureDrops.addAll(event.drops);
            event.drops.clear();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (getIsReady()) return;
        if (!captureDrops.isEmpty()) {
            dropCapture();
        }
    }

    public void dropCapture() {
        LOG.info("释放掉落物");
        List<ItemStack> mergedDrops = new ArrayList<>();
        // 遍历所有待合并的掉落物
        for (ItemStack stack : captureDrops) {
            boolean isMerged = false;
            // 检查已合并列表中是否存在同类可合并的堆叠
            for (ItemStack merged : mergedDrops) {
                if (areStacksMergeable(stack, merged)) {
                    merged.stackSize += stack.stackSize;
                    isMerged = true;
                    break;
                }
            }
            // 若未合并，则添加新堆叠到列表
            if (!isMerged) {
                mergedDrops.add(stack.copy()); // 必须复制原堆叠
            }
        }
        // 清空原始掉落物列表
        captureDrops.clear();
        // 生成合并后的实体
        Vector3f dropPos = Utils.getItemDropPos(player);
        for (ItemStack drop : mergedDrops) {
            EntityItem entityDrop = new EntityItem(
                world,
                dropPos.x,
                dropPos.y,
                dropPos.z,
                drop
            );
            world.spawnEntityInWorld(entityDrop);
        }
    }

    // 自定义合并条件（示例：匹配 Item、元数据和 NBT）
    public boolean areStacksMergeable(ItemStack a, ItemStack b) {
        return a.isItemEqual(b)
            && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    /*@SubscribeEvent
    @SideOnly(Side.SERVER)
    public void onTick(TickEvent.ServerTickEvent event) {
        // 没有启用则不进行遍历等操作
        if (!Config.dropItemToSelf) return;
        if (selfDrops.isEmpty()) return;
        final long startTime = System.currentTimeMillis();
        Iterator<Vector3i> iterator = selfDrops.iterator();
        while (iterator.hasNext() && System.currentTimeMillis() - startTime <= 10) {
            Vector3i pos = iterator.next();
            ConcurrentLinkedQueue<EntityItem> queue = GLOBAL_DROPS.get(pos);
            if (queue == null || queue.isEmpty()) {
                iterator.remove(); // 清理自身队列中的无效位置
                GLOBAL_DROPS.remove(pos); // 清理全局表空队列
                GlobalDropCleaner.lastGlobalChangeTime = System.currentTimeMillis();
                continue;
            }
            // 原子化取出并移除实体
            EntityItem ei = queue.poll();
            if (ei != null) {
                // 生成实体到世界
                Vector3f dropPos = Utils.getItemDropPos(player);
                ei.setPosition(dropPos.x, dropPos.y, dropPos.z);
                player.worldObj.spawnEntityInWorld(ei);
                // 若队列已空，清理全局表和自身队列
                if (queue.isEmpty()) {
                    iterator.remove();
                    GLOBAL_DROPS.remove(pos);
                    GlobalDropCleaner.lastGlobalChangeTime = System.currentTimeMillis();
                }
            }
        }
    }*/
}
