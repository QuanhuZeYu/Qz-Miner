package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.PreviewRender.RenderCache;
import club.heiqi.qz_miner.client.PreviewRender.SpaceCalculator;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import club.heiqi.qz_miner.shaderTools.ShaderManager;
import club.heiqi.qz_miner.utils.FileReadUtils;
import club.heiqi.qz_miner.utils.MatrixUtils;
import club.heiqi.qz_miner.utils.MessageUtils;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class BaseChainViewer {
    public static ShaderManager shader = new ShaderManager();
    public static boolean isShaderLoaded = false;
    public static RenderCache renderCache = new RenderCache();

    public Logger LOG = LogManager.getLogger();

    public EntityPlayer player;
    public Vector3i pos;
    public Block block;
    public int blockMeta;
    public TileEntity tileEntity;

    public BasePositionFounder positionFounder;
    public LinkedBlockingQueue<Vector3i> canBreakPositions = new LinkedBlockingQueue<>();

    public boolean inPressChainKey = false;
    public SpaceCalculator spaceCalculator = new SpaceCalculator();

    public BaseChainViewer(Vector3i pos) {
        this.pos = pos; this.player = Minecraft.getMinecraft().thePlayer;
        this.block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        this.blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        this.tileEntity = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        initShader();
        inPressChainKey = true;

        this.positionFounder = new BasePositionFounder(
                pos,
                canBreakPositions,
                player,
                new MinerConfig()
        );
        MyMod.parallelTick.addNormalTask(this.positionFounder);

        this.registry();
    }

    public boolean foundComplete = false;
    public static final long waitAddTimeMillisecond = 5; // 添加剔除顶点允许用时
    public static final int perTickMaxAdd = 64;
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (!(event.phase == TickEvent.RenderTickEvent.Phase.END)) return;
        if (!inPressChainKey) {
            this.unRegistry();
        }
        float particle = event.renderTickTime;

        // 取出所有结果 - 限定用时 - 限定数量
        long startTime = System.currentTimeMillis();
        int addCount = 0;
        if (!foundComplete) {
            ArrayList<Vector3i> points = new ArrayList<>(canBreakPositions);
            ArrayList<Vector3i> added = new ArrayList<>();
            for (Vector3i point : points) {
                spaceCalculator.add(point);
                added.add(point);
                addCount++;
                // 检查是否搜索完毕和加载完毕
                if (!spaceCalculator.hasChange && positionFounder.stopped.get()) {
                    foundComplete = true;
                    LOG.info("预览方块加载完毕");
                    MessageUtils.printSelfMessage("预览方块加载完毕");
                    break;
                }

                // 检查执行时间是否超时
                if (System.currentTimeMillis() - startTime > waitAddTimeMillisecond || addCount >= perTickMaxAdd) {
                    break; // 超时退出循环加点
                }
            }
            canBreakPositions.removeAll(added); // 移除已经添加过的

            // 传递数据到渲染数据中
            SpaceCalculator.VertexAndIndex vertexAndIndex = spaceCalculator.getVertexAndIndex();
            renderCache.updateData(vertexAndIndex.vertices, vertexAndIndex.indices);
        }

        shader.bind();

        Vector3f cameraPos = MatrixUtils.getCameraPos(particle);

        Matrix4f model = MatrixUtils.getModelMatrix(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f view = /* MatrixUtils.getViewMatrix(particle); */MatrixUtils.getModelViewByOriginal();
        Matrix4f projection = MatrixUtils.getProjectionByOriginal();


        shader.setUniformM4f("model", model);
        shader.setUniformM4f("view", view);
        shader.setUniformM4f("projection", projection);
        shader.setUniform3F("cameraPos", cameraPos);

        renderCache.render();

        shader.unbind();
    }

    public void initShader() {
        if (isShaderLoaded) return;

        String vertexSource = FileReadUtils.readText("assets/qz_miner/shader/MinerPreviewVertex.glsl");
        String fragmentSource = FileReadUtils.readText("assets/qz_miner/shader/MinerPreviewFragment.glsl");
        String geometrySource = FileReadUtils.readText("assets/qz_miner/shader/MinerPreviewGeometry.glsl");

        shader.loadShader(vertexSource, fragmentSource, geometrySource);

        isShaderLoaded = true;
    }

    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
        LOG.info("预览器已加载");
    }

    public void unRegistry() {
        positionFounder.interrupt();
        renderCache.updateData(SpaceCalculator.vertex, SpaceCalculator.index);
        FMLCommonHandler.instance().bus().unregister(this);
        LOG.info("预览器已卸载");
    }
}
