package club.heiqi.qz_miner.minerModes.chainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Strict;
import club.heiqi.qz_miner.minerModes.rightClicker.RightClicker;
import gregtech.api.metatileentity.CoverableTileEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public class GroupMode extends AbstractMode {
    public Logger LOG = LogManager.getLogger();
    public final BlockBreaker breaker;
    public final RightClicker rightClicker;
    int sampleMID = 0;
    public final Set<Group> whiteList = new HashSet<>();

    public GroupMode(ModeManager modeManager, Vector3i center) {
        super(modeManager, center);
        parseConfig();
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        breaker = new BlockBreaker(player, world);
        rightClicker = new RightClicker(player, world);
        timer = System.currentTimeMillis();
        if (!isInWhiteList()) return;
        positionFounder = new ChainFounder_Strict(this, center, player);
    }

    public int failCounter = 0;
    public long failTimer = 0;
    public long lastTime = System.currentTimeMillis();
    public int tickBreakCount = 0;
    public int allBreakCount = 0;
    @Override
    public void mainLogic() {
        lastTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - lastTime <= taskTimeLimit) {
            Vector3i pos = positionFounder.cache.poll();
            if (pos == null) {
                if (failCounter == 0) failTimer = System.currentTimeMillis();
                if (System.currentTimeMillis() - failTimer >= heartbeatTimeout) {
                    LOG.info("心跳超时结束");
                    shutdown(); // 没有获取到点的时间超过最大等待限制终止任务
                }
                failCounter++;
                return;
            }
            failCounter = 0;
            if (checkCanBreak(pos)) {
                if (isRenderMode.get()) modeManager.renderCache.add(pos);
                else if (isInteractMode.get()) {
                    rightClicker.rightClick(pos);
                    tickBreakCount++;
                    allBreakCount++;
                } else {
                    breaker.tryHarvestBlock(pos);
                    tickBreakCount++;
                    allBreakCount++;
                }
                // 判断挖掘数量是否终止
                if (allBreakCount >= Config.blockLimit) {
                    LOG.info("数量达到终止");
                    shutdown();
                    return;
                }
                if (tickBreakCount >= perTickBlock) break;
            }
        }
        tickBreakCount = 0;
    }

    public long timer;
    @Override
    public void unregister() {
        sendMessage();
        rightClicker.dropCapture();
        super.unregister();
    }

    public void sendMessage() {
        if (isRenderMode.get() || isInteractMode.get()) return;
        if (isShut) return;
        if (!modeManager.getPrintResult()) return;
        long totalTime = System.currentTimeMillis() - timer;
        // 分割秒和毫秒
        int seconds = (int)(totalTime / 1000);  // 秒数
        long milliseconds = totalTime % 1000;  // 毫秒数
        String message = "本次共挖掘: " + allBreakCount + "个方块"
            + " 共用时: " + seconds + "秒"
            + milliseconds + "毫秒";
        ChatComponentText text = new ChatComponentText(message);
        EntityPlayer player = modeManager.player;
        try {
            player.addChatMessage(text);
        }
        catch (Exception e) {
            LOG.error(e);
        }
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }



    // region 连锁组解析工具
    public static class Group {
        public String ID;
        public int meta;
        public int mID;
        public int blockIntID = -1; // -1代表无效

        public Group(String ID,int meta,int mID) {
            this.ID=ID;this.meta=meta;this.mID=mID;
            blockIntID = getBlockIdFromName(ID);
        }

        public Group(String parseIn) {
            String[] parsed = parseIn.split(",");
            String ID = "";
            int meta = 0;
            int mID = 0;

            for (String component : parsed) {
                component = component.trim(); // 去除前后空格
                String[] s = component.split(":", 2); // 限制分割元素为2
                if (s.length != 2) {
                    throw new IllegalArgumentException("Invalid component format: " + component);
                }
                String key = s[0].trim();
                String value = s[1].trim();

                // 处理键值对
                switch (key) {
                    case "stringID":
                        ID = value;
                        break;
                    case "blockMeta":
                        try {
                            meta = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid blockMeta: " + value);
                        }
                        break;
                    case "mID":
                        try {
                            mID = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid mID: " + value);
                        }
                        break;
                    default:
                        // 可记录警告或忽略未知键
                        break;
                }
            }

            // 验证必需字段（如ID是否非空）
            if (ID.isEmpty()) {
                throw new IllegalArgumentException("Missing required field: stringID");
            }

            this.ID = ID;
            this.meta = meta;
            this.mID = mID;
            // 查询数字ID
            blockIntID = getBlockIdFromName(ID);
        }

        public int getBlockIdFromName(String name) {
            return Block.getIdFromBlock((Block.getBlockFromName(name)));
        }
    }

    /**
     * 解析配置为对象结构
     */
    public void parseConfig() {
        String[] configList = Config.whiteList;
        for (String component : configList) {
            Group g = new Group(component);
            whiteList.add(g);
        }
    }

    /**
     * 判断样本是否在白名单中
     * @return
     */
    public boolean isInWhiteList() {
        if (tileSample instanceof CoverableTileEntity cT) {
            try {
                Field f = cT.getClass().getField("mID");
                f.setAccessible(true);
                sampleMID = f.getInt(cT);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                LOG.error("获取mID失败");
                return false;
            }
        }
        for (Group g : whiteList) {
            int id = g.blockIntID;
            int sampleID = Block.getIdFromBlock(blockSample);
            int meta = g.meta;
            int mID = g.mID;
            if (id == sampleID && meta == blockSampleMeta && mID == this.sampleMID) return true;
        }
        return false;
    }
    // endregion 连锁组解析工具
}

