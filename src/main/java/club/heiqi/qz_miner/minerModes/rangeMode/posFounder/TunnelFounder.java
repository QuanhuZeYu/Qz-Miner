package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3f;
import org.joml.Vector3i;

public class TunnelFounder extends PositionFounder {


    public TunnelFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
        Vector3f dir = getDirection();
        this.dir = getAxialDir(dir);
        this.dir = getAxialDir(getDirection());
        vertical = calculateVertical(getAxialDir(getDirection()));
        vertical1 = vertical[0];
        vertical2 = vertical[1];
    }

    public static int tunnelWidth = 3;
    public Vector3i dir;
    public int radius = 0;
    public int width = (tunnelWidth -1)/2;
    public Vector3i[] vertical;
    public Vector3i vertical1;
    public Vector3i vertical2;
    @Override
    public void mainLogic() {
        if (radius > radiusLimit) return;
        Vector3i cCenter = new Vector3i(
            center.x + dir.x * radius,
            center.y + dir.y * radius,
            center.z + dir.z * radius
        );
        for (int i = -width; i <= width; i++) {
            for (int j = -width; j <= width; j++) {
                Vector3i pos = new Vector3i(
                    cCenter.x + i * vertical2.x + j * vertical1.x,
                    cCenter.y + i * vertical2.y + j * vertical1.y,
                    cCenter.z + i * vertical2.z + j * vertical1.z
                );
                if (checkCanBreak(pos)) {
                    cache.add(pos); canBreakBlockCount++;
                }
            }
        }
        radius++;
    }

    public Vector3f getDirection() {
        double yawRadians = Math.toRadians(player.rotationYaw);
        double pitchRadians = Math.toRadians(player.rotationPitch);
        Vector3f vecForward = new Vector3f(
            (float) Math.sin(yawRadians) * ((float) -Math.cos(pitchRadians)),
            (float) -Math.sin(pitchRadians),
            (float) Math.cos(yawRadians) * (float) Math.cos(pitchRadians)
        );
        return vecForward;
    }

    public Vector3i getAxialDir(Vector3f vec) {
        Vector3i axialForward = new Vector3i();
        float signX = Math.signum(vec.x);
        float signY = Math.signum(vec.y);
        float signZ = Math.signum(vec.z);

        float absX = Math.abs(vec.x);
        float absY = Math.abs(vec.y);
        float absZ = Math.abs(vec.z);

        if (absX > absY && absX > absZ) {
            axialForward.x = (int) signX;
            axialForward.y = 0;
            axialForward.z = 0;
        } else if (absY > absX && absY > absZ) {
            axialForward.x = 0;
            axialForward.y = (int) signY;
            axialForward.z = 0;
        } else {
            axialForward.x = 0;
            axialForward.y = 0;
            axialForward.z = (int) signZ;
        }
        return axialForward;
    }

    public Vector3i[] calculateVertical(Vector3i vec) {
        Vector3i vertical1 = new Vector3i();
        Vector3i vertical2 = new Vector3i();
        Vector3i[] result = new Vector3i[2];
        if (vec.x != 0) {
            vertical1.set(0, 1, 0);
            vertical2.set(0, 0, 1);
        } else if (vec.y != 0) {
            vertical1.set(1, 0, 0);
            vertical2.set(0, 0, 1);
        } else {
            vertical1.set(1, 0, 0);
            vertical2.set(0, 1, 0);
        }
        result[0] = vertical1;
        result[1] = vertical2;
        return result;
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
