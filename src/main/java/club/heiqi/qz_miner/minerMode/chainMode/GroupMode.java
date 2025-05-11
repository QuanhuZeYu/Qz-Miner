package club.heiqi.qz_miner.minerMode.chainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerMode.enums.Sides;
import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import club.heiqi.qz_miner.minerMode.chainMode.posFounder.ChainFounder_Strict;
import gregtech.api.metatileentity.CoverableTileEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public class GroupMode extends AbstractMode {
    public Logger LOG = LogManager.getLogger();
    int sampleMID = 0;
    public final Set<Group> whiteList = new HashSet<>();

    public GroupMode(ModeManager modeManager, Vector3i center, Sides sides) {
        super(modeManager, center, sides);
        parseConfig();
        EntityPlayer player = modeManager.player;
        timer = System.currentTimeMillis();
        positionFounder = new ChainFounder_Strict(this);
        addPreUnregisterTask(this::sendMessage);
        if (!isInWhiteList()) initSuccess = false; // 如果挖掘样本不在白名单内取消模式创建
    }

    /**用于追踪模式运行时间*/
    public long timer;
    public void sendMessage() {
        if (side == Sides.CLIENT) return;
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

