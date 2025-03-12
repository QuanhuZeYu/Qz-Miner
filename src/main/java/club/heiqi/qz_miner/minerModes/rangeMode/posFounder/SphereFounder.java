package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

public class SphereFounder extends PositionFounder {
    public int radius = 1;
    public List<Vector3i> tempCache = new ArrayList<>();

    public SphereFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
    }

    @Override
    public void mainLogic() {
        if (radius > radiusLimit) return;
        for (int dxSign : new int[]{-1,1}) {
            int dx = dxSign * radius * center.x;
            for (int dy = center.y-radius; dy <= center.y+radius; dy++) {
                for (int dz = center.z-radius; dz <= center.z+radius; dz++) {
                    putPoint(dx, dy, dz);
                }
            }
        }
        for (int dySign : new int[]{-1,1}) {
            int dy = dySign * radius * center.y;
            for (int dx = center.x-radius; dx <= center.x+radius; dx++) {
                for (int dz = center.z-radius; dz <= center.z+radius; dz++) {
                    putPoint(dx, dy, dz);
                }
            }
        }
        for (int dzSign : new int[]{-1,1}) {
            int dz = dzSign * radius * center.z;
            for (int dy = center.y-radius; dy <= center.y+radius; dy++) {
                for (int dx = center.x-radius; dz <= center.x+radius; dz++) {
                    putPoint(dx, dy, dz);
                }
            }
        }
        cache.addAll(sort(tempCache));
        tempCache = new ArrayList<>();
        radius++;
    }

    public void putPoint(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        if (checkCanBreak(pos) && checkInSphere(pos)) {
            tempCache.add(pos);
            sendHeartbeat();
        }
    }

    public boolean checkInSphere(Vector3i pos) {
        int distanceSquared = radius*radius;
        int actualDistanceSquared = (center.x - pos.x) * (center.x - pos.x)
                                  + (center.y - pos.y) * (center.y - pos.y)
                                  + (center.z - pos.z) * (center.z - pos.z);
        return actualDistanceSquared <= distanceSquared;
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
