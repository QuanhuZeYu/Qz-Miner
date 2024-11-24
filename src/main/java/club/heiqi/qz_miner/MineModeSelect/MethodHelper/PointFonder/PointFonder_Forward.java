package club.heiqi.qz_miner.MineModeSelect.MethodHelper.PointFonder;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class PointFonder_Forward extends PointFonder {
    public EntityLivingBase player;
    public Vector3i forwardVector = new Vector3i(0);
    public List<Vector3i> verticalForward = new ArrayList<>();

    public int i = -1, j = -1, l = 0;

    public PointFonder_Forward(Point point, EntityLivingBase player) {
        super(point);
        this.player = player;
        double yawRadians = Math.toRadians(player.rotationYaw);
        double pitchRadians = Math.toRadians(player.rotationPitch);
        Vector3f vecForward = new Vector3f(
            (float) (-Math.cos(pitchRadians) * Math.sin(yawRadians)), // X分量
            (float) (-Math.sin(pitchRadians)),                        // Y分量
            (float) (Math.cos(pitchRadians) * Math.cos(yawRadians))   // Z分量
        );
        alignToAxis(vecForward);
        getVerticalForward();
    }

    @Override
    public Collection<Point> getAll() {
        if(cache.isEmpty()) return null;
        List<Point> ret = new ArrayList<>();
        Iterator<Point> it = cache.iterator();
        while(it.hasNext()) {
            Point point = it.next();
            it.remove();
            ret.add(point);
        }
        return ret;
    }

    @Override
    public void startPhase() {
        /*for (int l = this.l; l< Config.radiusLimit; l++, this.l++) {
            logger.info("当前半径: {}", l);
            x = centerP.x + forwardVector.x * l;
            y = centerP.y + forwardVector.y * l;
            z = centerP.z + forwardVector.z * l;
            Vector3i vec1 = verticalForward.get(0);
            Vector3i vec2 = verticalForward.get(1);
            for (int i = this.i; i <= 1; i++, this.i++) { // 起始面偏移
                for (int j = this.j; j <= 1; j++, this.j++) {
                    int offsetX = x + i * vec1.x + j * vec2.x;
                    int offsetY = y + i * vec1.y + j * vec2.y;
                    int offsetZ = z + i * vec1.z + j * vec2.z;
                    cache.add(new Point(offsetX, offsetY, offsetZ));
                    if (checkCacheOverSize() || checkShouldStop()) {
                        this.j++;
                        return; // 退出startPhase后会回到上层while并自动设置state
                    }
                }
            }
            this.i = this.j = -1; // 重置i,j
        }
        this.i = this.j = -1; // 重置i,j*/
        // 遍历每一个 z 层
        for (int l = this.l; l < Config.radiusLimit; l++, this.l++) {
            x = centerP.x + forwardVector.x * l;
            y = centerP.y + forwardVector.y * l;
            z = centerP.z + forwardVector.z * l;
            // 获取垂直向量
            Vector3i vec1 = verticalForward.get(0);
            Vector3i vec2 = verticalForward.get(1);
            List<Point> face = new ArrayList<>();
            // 对 i 和 j 进行 -1 到 1 的遍历，生成 3x3 的矩形
            for (int i = this.i; i <= 1; i++) {  // 遍历垂直于第一个垂直向量的方向
                for (int j = this.j; j <= 1; j++) {  // 遍历垂直于第二个垂直向量的方向
                    // 根据偏移量生成点
                    int offsetX = x + i * vec1.x + j * vec2.x;
                    int offsetY = y + i * vec1.y + j * vec2.y;
                    int offsetZ = z + i * vec1.z + j * vec2.z;
                    // 添加点到缓存
                    cache.add(new Point(offsetX, offsetY, offsetZ));
                    // 检查缓存是否超过大小或是否需要停止
                    if (checkCacheOverSize() || checkShouldStop()) {
                        this.j++; this.i = i;
                        return; // 退出startPhase后会回到上层while并自动设置state
                    }
                }
            }
            this.i = this.j = -1; // 重置i,j
        }
        this.i = this.j = -1; // 重置i,j
        completePhase();
    }

    public void alignToAxis(Vector3f forwardVector) {
        if (forwardVector == null) {
            throw new IllegalArgumentException("朝向数据为空");
        }

        // 检查是否为零向量
        if (forwardVector.length() == 0) {
            throw new IllegalArgumentException("朝向数据(0, 0, 0)异常, 需非0向量");
        }

        // 计算每个分量的符号
        float signX = Math.signum(forwardVector.x);
        float signY = Math.signum(forwardVector.y);
        float signZ = Math.signum(forwardVector.z);

        // 计算每个分量的绝对值
        float absX = Math.abs(forwardVector.x);
        float absY = Math.abs(forwardVector.y);
        float absZ = Math.abs(forwardVector.z);

        // 根据最大值设置主轴
        if (absX >= absY && absX >= absZ) {
            this.forwardVector.x = (int) signX; // 保留符号
            this.forwardVector.y = 0;  // 置为零
            this.forwardVector.z = 0;  // 置为零
        } else if (absY >= absX && absY >= absZ) {
            this.forwardVector.x = 0;  // 置为零
            this.forwardVector.y = (int) signY; // 保留符号
            this.forwardVector.z = 0;  // 置为零
        } else {
            this.forwardVector.x = 0;  // 置为零
            this.forwardVector.y = 0;  // 置为零
            this.forwardVector.z = (int) signZ; // 保留符号
        }
    }

    public void getVerticalForward() {
        verticalForward.clear();
        // 根据 forwardVector 来决定垂直方向
        if (forwardVector.x != 0) {
            verticalForward.add(new Vector3i(0, 1, 0)); // Y轴
            verticalForward.add(new Vector3i(0, 0, 1)); // Z轴
        } else if (forwardVector.y != 0) {
            verticalForward.add(new Vector3i(1, 0, 0)); // X轴
            verticalForward.add(new Vector3i(0, 0, 1)); // Z轴
        } else if (forwardVector.z != 0) {
            verticalForward.add(new Vector3i(1, 0, 0)); // X轴
            verticalForward.add(new Vector3i(0, 1, 0)); // Y轴
        }
    }
}
