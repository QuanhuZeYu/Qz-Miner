package club.heiqi.qz_miner.utils;

import java.util.ArrayList;

public class ArrayConverter {
    public static float[] convertF(ArrayList<float[]> list) {
        // 步骤1: 计算总长度
        int totalLength = 0;
        for (float[] array : list) {
            totalLength += array.length;
        }

        // 步骤2: 创建目标数组
        float[] result = new float[totalLength];

        // 步骤3: 复制数据
        int currentPos = 0;
        for (float[] array : list) {
            System.arraycopy(array, 0, result, currentPos, array.length);
            currentPos += array.length;
        }

        return result;
    }

    public static int[] convertI(ArrayList<int[]> list) {
        // 1. 计算总长度
        int totalLength = 0;
        for (int[] array : list) {
            totalLength += array.length;
        }

        // 2. 创建目标数组
        int[] result = new int[totalLength];

        // 3. 高效复制数据
        int offset = 0;
        for (int[] array : list) {
            System.arraycopy(
                    array,           // 源数组
                    0,               // 源数组起始位置
                    result,          // 目标数组
                    offset,          // 目标数组起始位置
                    array.length     // 复制长度
            );
            offset += array.length;
        }

        return result;
    }
}
