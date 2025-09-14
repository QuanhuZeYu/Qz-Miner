package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class TunnelBlastingFounder extends BasePositionFounder {
    public TunnelBlastingFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
        setName("隧道搜索器");
    }

    @Override
    public void run1() {
        int curRadius = 0;
        Vector3i lookAxisDir = getAxisAlignedLookDir();
        ArrayList<Vector3i> verticals = getVerticalAxisComponent(lookAxisDir);
        Vector3i verticalA = verticals.get(0);
        Vector3i verticalB = verticals.get(1);
        int tunnelRadius = minerConfig.tunnelWidth; // 隧道半径 轴向半径的方形
        while (curCount < minerConfig.blockLimit && curRadius < minerConfig.bigRadius) {
            // 沿主方向延伸
            Vector3i mainDirPoint = new Vector3i(lookAxisDir).mul(curRadius);

            // 遍历横截面
            for (int a = -tunnelRadius; a <= tunnelRadius; a++) {
                for (int b = -tunnelRadius; b <= tunnelRadius; b++) {
                    Vector3i point = new Vector3i(center)
                            .add(mainDirPoint)
                            .add(new Vector3i(verticalA).mul(a))
                            .add(new Vector3i(verticalB).mul(b));

                    if (!checkCanAdd(point)) continue;
                    addResult(point);

                    waitUntil();
                    // 检查方块数量限制
                    if (curCount >= minerConfig.blockLimit) {
                        return;
                    }
                }
            }

            waitUntil();
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("线程被中断");
                return;
            }
            curRadius++;
        }
        // LOG.info("结束时半径: {}; 找到数量: {}", curRadius, foundedPositions.size());
    }

    public Vector3i getAxisAlignedLookDir() {
        EntityPlayer player = this.player;
        float yaw = player.rotationYaw % 360;
        float pitch = player.rotationPitch;

        if (pitch > 45) return new Vector3i(0,-1,0);
        else if (pitch < -45) return new Vector3i(0,1,0);
        else if (yaw >= 315 || yaw < 45) return new Vector3i(0,0,1);
        else if (yaw >= 45 && yaw < 135) return new Vector3i(-1,0,0);
        else if (yaw >= 135 && yaw < 225) return new Vector3i(0,0,-1);
        else  return new Vector3i(1,0,0);
    }

    public ArrayList<Vector3i> getVerticalAxisComponent(Vector3i axisDir) {
        int x = axisDir.x;
        int y = axisDir.y;
        int z = axisDir.z;

        ArrayList<Vector3i> results = new ArrayList<>();
        if (x == 0) {
            results.add(new Vector3i(1,0,0));
        }
        if (y == 0) {
            results.add(new Vector3i(0,1,0));
        }
        if (z == 0) {
            results.add(new Vector3i(0,0,1));
        }
        return results;
    }
}
