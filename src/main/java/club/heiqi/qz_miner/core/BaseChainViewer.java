package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.RenderCache;
import club.heiqi.qz_miner.client.SpaceCalculator;
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

        this.positionFounder = new BasePositionFounder(pos, canBreakPositions, player, Config.bigRadius, Config.blockLimit);
        MyMod.parallelTick.addPreServerTickTask(this.positionFounder);

        this.registry();
    }

    public boolean foundComplete = false;
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (!inPressChainKey) {
            this.unRegistry();
        }
        if (canBreakPositions.isEmpty()) {
            return;
        }
        float particle = event.renderTickTime;

        // 取出所有结果
        if (!foundComplete) {
            ArrayList<Vector3i> points = new ArrayList<>(canBreakPositions);
            for (Vector3i point : points) {
                spaceCalculator.add(point);
                // 检查是否搜索完毕和加载完毕
                if (!spaceCalculator.hasChange && positionFounder.stopped.get()) {
                    foundComplete = true;
                }
            }
        }

        // 传递数据到渲染数据中
        if (spaceCalculator.hasChange) {
            SpaceCalculator.VertexAndIndex vertexAndIndex = spaceCalculator.getVertexAndIndex();
            renderCache.updateData(vertexAndIndex.vertices, vertexAndIndex.indices);
        }

        shader.bind();

        Matrix4f model = MatrixUtils.getModelMatrix(0,0,0);
        Matrix4f view = MatrixUtils.getViewMatrix(particle);
        Matrix4f projection = MatrixUtils.getProjectionMatrix();
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;;

        shader.setUniformM4f("model", model);
        shader.setUniformM4f("view", view);
        shader.setUniformM4f("projection", projection);
        // shader.setUniform3F("cameraPos", new Vector3f((float) player.posX, (float) player.posY, (float) player.posZ));

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
    }

    public void unRegistry() {
        positionFounder.interrupt();
        renderCache.updateData(SpaceCalculator.vertex, SpaceCalculator.index);
        FMLCommonHandler.instance().bus().unregister(this);
    }
}
