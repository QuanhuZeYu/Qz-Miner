package club.heiqi.qz_miner.statueStorage;

import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为跟踪器，跟踪玩家操作并记录
 */
public class PlayerTracer {
    // 日期格式化器（线程安全）
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMATTER = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    /**总表记录*/
    public static Map<String,Map<String,Map<String,List<Map<String,Object>>>>> GLOBAL_TRACE = new HashMap<>();

    /**
     * 长期记录器 - 以天为分隔 - 键形式为 年-月-日 - 值形式 操作记录表 {操作类型：详情列表}
     * {日期: {
     *     操作类型01: [detail...],
     *     操作类型02: [detail...],
     *     ...
     * }}
     * */
    public Map<String, Map<String,List<Map<String,Object>>>> dayCategorize= new HashMap<>(); // 长期记录器 - 以天为分隔

    public PlayerTracer(EntityPlayer player) {
        String name = player.getDisplayName();
        UUID uuid = player.getUniqueID();
        String key = name+"_"+uuid;
        if (!GLOBAL_TRACE.containsKey(key)) {
            GLOBAL_TRACE.put(name + "_" + uuid, dayCategorize);
        } else {
            dayCategorize = GLOBAL_TRACE.get(key);
        }
    }

    /** 添加操作记录 */
    public void addOperate(OpRecord record) {
        // 获取日期键
        String dayKey = DATE_FORMATTER.get().format(new Date(record.millisecond));

        // 获取当天的操作记录Map（线程安全）-> {操作类型：[]}
        Map<String, List<Map<String,Object>>> dayRecords = dayCategorize.computeIfAbsent(
                dayKey,
                k -> new ConcurrentHashMap<>()
        );

        // 获取该操作类型的记录列表（线程安全）
        List<Map<String,Object>> opList = dayRecords.computeIfAbsent(
                record.opType,
                k -> Collections.synchronizedList(new ArrayList<>())
        );

        // 添加记录（同步块保证线程安全）
        synchronized(opList) {
            opList.add(record.detail);
        }
    }





    public static class OpRecord {
        /**操作时间*/
        public long millisecond;
        public String opType;
        public Map<String,Object> detail;

        public OpRecord(String opType,long millisecond,@Nullable Map<String,Object> detail) {
            this.opType = opType;
            this.millisecond = millisecond;
            this.detail = detail == null ? new HashMap<>() : detail;
            this.detail.put("时间戳",String.valueOf(millisecond));
        }
    }
}
