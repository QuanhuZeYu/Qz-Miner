package club.heiqi.qz_miner.client.cubeRender;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.*;

public class SpaceCalculator {
    public static Logger LOG = LogManager.getLogger();
    public Map<Vector3i, SpacePoint> points = new HashMap<>();

    /**
     * 一次性计算方案
     * @param points
     */
    public SpaceCalculator(List<Vector3i> points) {
        for (Vector3i v : points) {
            SpacePoint p = new SpacePoint(v, this);
            this.points.put(v, p);
        }
        // 放置完成后开始计算邻面
        for (Map.Entry<Vector3i, SpacePoint> vector3iPointEntry : this.points.entrySet()) {
            SpacePoint p = vector3iPointEntry.getValue();
            p.checkNeighbor();
        }
    }

    public void addPoint(Vector3i p) {
        SpacePoint spacePoint = new SpacePoint(p, this);
        points.put(p, spacePoint);
        spacePoint.checkNeighbor();
    }

    public void addPoints(List<Vector3i> points) {
        for (Vector3i v : points) {
            if (this.points.containsKey(v)) {
                continue;
            }
            addPoint(v);
        }
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public void clear() {
        points = new HashMap<>();
    }

    public static class SpacePoint {
        public SpaceCalculator papa;
        public List<Integer> faces = Arrays.asList(0,1,2,3,4,5); // 无邻居面数组
        public final Vector3i self;
        public Set<RenderCube.Face> deFaces = new HashSet<>(); // 有邻居数组

        public SpacePoint(Vector3i point, SpaceCalculator parent) {
            self = point; papa = parent;
        }

        public void checkNeighbor() {
            for (int i : faces) { // 遍历6个面
                // 如果已经有邻居 检查邻居减面状态
                if (deFaces.contains(RenderCube.Face.values()[i])) {
                    RenderCube.Face face = RenderCube.Face.values()[i]; // 当前面
                    Vector3i ne = new Vector3i(self).add(face.faceVec); // 获取邻居坐标
                    SpacePoint neP = papa.points.get(ne); // 获取邻居对象
                    neP.deFaces.add(face.getOppositeFace()); // 邻居减面数据添加
                }
                // 如果没有邻居
                else {
                    RenderCube.Face face = RenderCube.Face.values()[i]; // 当前面
                    Vector3i ne = new Vector3i(self).add(face.faceVec); // 获取邻居坐标
                    if (papa.points.containsKey(ne)) {
                        //如果有邻居
                        deFaces.add(face);
                        SpacePoint neP = papa.points.get(ne); // 获取邻居对象
                        neP.deFaces.add(face.getOppositeFace()); // 邻居减面数据添加
                    }
                }
            }
        }
    }
}
