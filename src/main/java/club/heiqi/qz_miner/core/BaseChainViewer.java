package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.PreviewRender.RenderCache;
import club.heiqi.qz_miner.client.PreviewRender.SpaceCalculator;
import club.heiqi.qz_miner.core.founder.BasePositionFounder;
import club.heiqi.qz_miner.shaderTools.ShaderManager;
import club.heiqi.qz_miner.utils.FileReadUtils;
import club.heiqi.qz_miner.utils.MatrixUtils;
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
        if (!Config.usePreview) return;  // 如果配置关闭预览则不做任何事情
        this.pos = pos; this.player = Minecraft.getMinecraft().thePlayer;
        this.block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        this.blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        this.tileEntity = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);

        initShader();
        inPressChainKey = true;

        this.positionFounder = ((ClientProxy)MyMod.proxy).clientState.minerModeState.createPositionFounder(
                pos,
                canBreakPositions,
                player,
                new MinerConfig()
        );
        MyMod.parallelTick.addNormalTask(this.positionFounder);

        this.registry();
    }

    public boolean foundComplete = false;
    public boolean addComplete = false;
    public static final long waitAddTimeMillisecond = 5; // 添加剔除顶点允许用时
    public static final int perTickMaxAdd = 64;
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (!(event.phase == TickEvent.RenderTickEvent.Phase.END)) return;
        if (!inPressChainKey) {
            LOG.info("运行中终止");
            this.unRegistry();
        }
        float particle = event.renderTickTime;

        // 取出所有结果 - 限定用时 - 限定数量
        long startTime = System.currentTimeMillis();
        int addCount = 0;
        if (!foundComplete || !addComplete) {
            // 检查是否传递完毕
            if (positionFounder.stopped.get()) foundComplete = true;
            while (System.currentTimeMillis() - startTime < waitAddTimeMillisecond && addCount < perTickMaxAdd) {
                Vector3i point = canBreakPositions.poll();
                if (point != null) {
                    spaceCalculator.add(point);
                    addCount++;
                }
            }
            if (!spaceCalculator.hasChange && foundComplete) addComplete = true;

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
