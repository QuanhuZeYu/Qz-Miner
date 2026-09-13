package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T31 波次 6 喂入序工具：公共 begin/build 自 72fd97e 起按**生产快照序（最新→最早）**解释输入，
 * 探针夹具一律以时间序（最早→最新）表达期望，喂入前经本类翻转为生产快照序。
 */
public final class VerifyFeeds {

    private VerifyFeeds() {
    }

    /** @return 时间序列表的生产快照序副本（最新在前） */
    public static <T> List<T> snapshot(List<T> chronological) {
        List<T> copy = new ArrayList<T>(chronological);
        Collections.reverse(copy);
        return copy;
    }

    /** @return 与时间序列表同序的类别载体翻转为生产快照序 */
    public static int[] snapshot(int[] chronological) {
        if (chronological == null) {
            return null;
        }
        int[] copy = new int[chronological.length];
        for (int index = 0; index < chronological.length; index++) {
            copy[index] = chronological[chronological.length - 1 - index];
        }
        return copy;
    }
}
