package club.heiqi.qz_miner.client.cubeRender;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public class RenderCube {
    public static Logger LOG = LogManager.getLogger();
    public static int vao;
    public static int verticesID;
    public static boolean isInit = false;
    public int eboID; // 每个实例有自己的EBO
    public DefaceList defaces;


    public static float[] vertices = new float[] {
        // top
        -0.5f, 0.5f, -0.5f,/*0*/ -0.5f, 0.5f, 0.5f,/*1*/ 0.5f, 0.5f, 0.5f,/*2*/ 0.5f, 0.5f, -0.5f,/*3*/
        // bottom
        -0.5f, -0.5f, -0.5f,/*4*/ -0.5f, -0.5f, 0.5f,/*5*/ 0.5f, -0.5f, 0.5f,/*6*/ 0.5f, -0.5f, -0.5f /*7*/
    };


    /**
     * 适用于 GL_LINES 的索引，每个面包含 4 条线（8 个索引）
     * 格式说明：{起点1, 终点1, 起点2, 终点2, ...}
     */
    public static List<Integer> listIndex = Arrays.asList(
        // 顶面 (原 {0,1,2,3} → 生成 0-1, 1-2, 2-3, 3-0)
        0,1, 1,2, 2,3, 3,0,    // 面 0: TOP
        // xN 面 (原 {0,4,5,1} → 生成 0-4,4-5,5-1,1-0)
        0,4, 4,5, 5,1, 1,0,    // 面 1: XN
        // zN 面 (原 {0,3,7,4} → 生成 0-3,3-7,7-4,4-0)
        0,3, 3,7, 7,4, 4,0,    // 面 2: ZN
        // xP 面 (原 {2,6,7,3} → 生成 2-6,6-7,7-3,3-2)
        2,6, 6,7, 7,3, 3,2,    // 面 3: XP
        // zP 面 (原 {1,2,6,5} → 生成 1-2,2-6,6-5,5-1)
        1,2, 2,6, 6,5, 5,1,    // 面 4: ZP
        // 底面 (原 {4,7,6,5} → 生成 4-7,7-6,6-5,5-4)
        4,7, 7,6, 6,5, 5,4     // 面 5: BOTTOM
    );

    public static Map<Face, List<Integer>> mapIndex = new HashMap<>();
    static {
        mapIndex.put(Face.TOP, Arrays.asList(0,1, 1,2, 2,3, 3,0));      // 面 0: TOP
        mapIndex.put(Face.XN, Arrays.asList(0,4, 4,5, 5,1, 1,0));       // 面 1: XN
        mapIndex.put(Face.ZN, Arrays.asList(0,3, 3,7, 7,4, 4,0));       // 面 2: ZN
        mapIndex.put(Face.XP, Arrays.asList(2,6, 6,7, 7,3, 3,2));       // 面 3: XP
        mapIndex.put(Face.ZP, Arrays.asList(1,2, 2,6, 6,5, 5,1));       // 面 4: ZP
        mapIndex.put(Face.BOTTOM, Arrays.asList(4,7, 7,6, 6,5, 5,4));   // 面 5: BOTTOM
    }

    /**
     * 存储减面情况与之对应的vao ebo
     */
    public static Map<DefaceList, RenderCube> connect = new HashMap<>();

    public RenderCube(DefaceList defaces) {
        this.defaces = defaces;
        calculateIndex(defaces);
        setupEBO(); // 初始化EBO
        connect.put(defaces, this);
    }

    private void setupEBO() {
        // 生成并绑定EBO
        eboID = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboID);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indexes.length);
        indexBuffer.put(indexes).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0); // 解绑
    }

    public void dynamicCreate() {
        if (isInit) return;
        int curProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(0);
        {
            vao = glGenVertexArrays();
            verticesID = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, verticesID);
            FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
            buffer.put(vertices).flip();
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
            glVertexPointer(3, GL_FLOAT, 0, 0);
            glEnableClientState(GL_VERTEX_ARRAY); // 启用顶点数组

            // 解绑
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        glUseProgram(curProgram);
        isInit = true;
    }
    public int[] indexes;
    public void render() {
        if (!isInit) {
            dynamicCreate();
        }
        glBindVertexArray(vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboID); // 绑定EBO
        glDrawElements(GL_LINES, indexes.length, GL_UNSIGNED_INT, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * 提取需要减去的索引 两个int为一组 然后复制listIndex进行查找 以两个为一组进行无序对比，找到就删除对应的索引
     * 将得到的结果存储到int[] indexes
     * @param defaces 减面数组
     */
    public void calculateIndex(DefaceList defaces) {
        // 1. 复制原始索引列表（所有面的线段）
        List<Integer> remainingIndices = new ArrayList<>(listIndex);
        // 2. 遍历需要移除的每个面
        for (Face face : defaces.elements) {
            // 获取剔除面对应的所有线段（8个索引，4条线）
            List<Integer> faceIndices = mapIndex.get(face);
            if (faceIndices == null) continue;
            // 3. 遍历该面的每条线段（两个索引为一组）
            for (int i = 0; i < faceIndices.size(); i += 2) {
                int start = faceIndices.get(i);
                int end = faceIndices.get(i + 1);
                // 4. 在 remainingIndices 中标记匹配的线段为 null
                for (int j = 0; j < remainingIndices.size() - 1; j += 2) {
                    Integer currentStart = remainingIndices.get(j);
                    Integer currentEnd = remainingIndices.get(j + 1);
                    // 跳过已标记为 null 的位置
                    if (currentStart == null || currentEnd == null) continue;
                    // 检查线段是否匹配（支持无序对比）
                    if ((currentStart == start && currentEnd == end) ||
                        (currentStart == end && currentEnd == start)) {
                        // 标记这两个位置为 null（保持索引不变）
                        remainingIndices.set(j, null);
                        remainingIndices.set(j + 1, null);
                    }
                }
            }
        }
        // 5. 移除所有 null 值（保留有效线段）
        remainingIndices.removeIf(Objects::isNull);
        // 6. 转换为 int 数组
        indexes = remainingIndices.stream().mapToInt(Integer::intValue).toArray();
        LOG.info("indexSize:{}",indexes.length);
    }

    public static void render(DefaceList defaces) {
        RenderCube cube;
        if (connect.containsKey(defaces)) {
            cube = connect.get(defaces);
        }
        else {
            cube = new RenderCube(defaces);
        }
        cube.render();
    }

    public static int[] flattenIndex(List<List<Integer>> index) {
        int totalSize = 0;
        for (List<Integer> sublist : index) {
            if (sublist != null) { // 处理可能的null子列表
                totalSize += sublist.size();
            }
        }
        int[] result = new int[totalSize];
        int pos = 0;
        for (List<Integer> sublist : index) {
            if (sublist != null) { // 忽略null子列表
                for (int num : sublist) {
                    result[pos++] = num;
                }
            }
        }
        return result;
    }

    public enum Face {
        TOP(0,5,new Vector3i(0,1,0)),
        XN(1,3,new Vector3i(-1,0,0)),
        ZN(2,4,new Vector3i(0,0,-1)),
        XP(3,1,new Vector3i(1,0,0)),
        ZP(4,2,new Vector3i(0,0,1)),
        BOTTOM(5,0,new Vector3i(0,-1,0));

        final int index;
        final int opposite;
        final Vector3i faceVec;

        Face(int index, int opposite, Vector3i vec) {
            this.index = index; this.opposite = opposite; this.faceVec = vec;
        }
        public Face getOppositeFace() {
            return Face.values()[Face.values()[index].opposite];
        }
    }

    public static class DefaceList {
        public final Set<Face> elements;
        public DefaceList(Set<Face> elements) {
            this.elements = new HashSet<>(elements);
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (elements.size() != ((DefaceList) o).elements.size()) return false;
            for (Face e : elements) {
                if (!((DefaceList) o).elements.contains(e)) return false;
            }
            return true;
        }
        @Override
        public int hashCode() {
            int result = 0;
            for (Face f : elements) {
                result += f.index;
            }
            return result;
        }
    }






    public static void renderFullCube() {
        // 前面
        glVertex3f(0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, 0.5f, 0.5f);
        glVertex3f(-0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, -0.5f, 0.5f);
        glVertex3f(-0.5f, -0.5f, 0.5f); glVertex3f(0.5f, -0.5f, 0.5f);
        glVertex3f(0.5f, -0.5f, 0.5f); glVertex3f(0.5f, 0.5f, 0.5f);
        // 后面
        glVertex3f(0.5f, 0.5f, -0.5f); glVertex3f(-0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, 0.5f, -0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f); glVertex3f(0.5f, -0.5f, -0.5f);
        glVertex3f(0.5f, -0.5f, -0.5f); glVertex3f(0.5f, 0.5f, -0.5f);
        // 链接前后
        glVertex3f(0.5f, 0.5f, 0.5f); glVertex3f(0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, 0.5f, -0.5f);
        glVertex3f(-0.5f, -0.5f, 0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
        glVertex3f(0.5f, -0.5f, 0.5f); glVertex3f(0.5f, -0.5f, -0.5f);
    }
}
