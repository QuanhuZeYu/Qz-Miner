package club.heiqi.qz_miner.client.lootGame;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import club.heiqi.qz_miner.util.CheckCompatibility;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static club.heiqi.qz_miner.client.renderSelect.RenderSelect.camPos;
import static org.lwjgl.opengl.GL11.*;

@SideOnly(Side.CLIENT)
public class RenderMines {
    public static NumberFormat format = NumberFormat.getIntegerInstance();
    public static long coolDown = (long) (Config.coolDown * 1_000_000_000L);
    public static Map<UUID, ArrayList<Vector3i>> findBombMap = new HashMap<>();
    public ArrayList<Vector3i> findBomb = new ArrayList<>();
    public boolean inReady = false;
    public boolean needRender = false;
    public long durationReady = 0L;
    public long lastUse = 0L;

    @SubscribeEvent
    public void onPlayerJoin(TickEvent.PlayerTickEvent event) {
        if (event.side == Side.CLIENT) {
            UUID uuid = event.player.getUniqueID();
            if (!findBombMap.containsKey(uuid)) {
                findBombMap.put(uuid, new ArrayList<>());
            }
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void findMines(TickEvent.PlayerTickEvent event) {
        if (!CheckCompatibility.isHasClass_MSMTile) {
            unregister();
            return;
        }
        ModeManager modeManager = SelfStatue.modeManager;
        if (modeManager == null) return;
        if (modeManager.getIsReady()) {
            if (!inReady) { // 首次进入设置状态
                inReady = true;
                durationReady = System.nanoTime();
            }
            else { // 持续状态下进行计时
                durationReady = System.nanoTime() - durationReady;
                if (durationReady > 5000000000L) { // 持续按压了5s
                    if (System.nanoTime() - lastUse > coolDown) { // 上次使用时间到现在超过了冷却时间
                        needRender = true;
                        lastUse = System.nanoTime();
                        //MY_LOG.LOG.info("准备渲染地雷");
                    }
                }
            }
        } else {
            if (inReady) inReady = false;
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void renderBomb(DrawBlockHighlightEvent event) {
        if (!CheckCompatibility.isHasClass_MSMTile) return;
        if (needRender && findBomb.isEmpty()) {
            ArrayList<Vector3i> findBomb = new ArrayList<>(findBombMap.get(event.player.getUniqueID()));
            if (findBomb.isEmpty()) return;

            /*findBombMap.forEach((uuid, bb) -> {
                StringBuilder sb = new StringBuilder();
                bb.forEach(b -> sb.append(b.toString(format)).append(", "));
                sb.delete(sb.length() - 2, sb.length());
                MY_LOG.LOG.info(sb.toString());
            });*/
            Vector3i playerPos = new Vector3i((int) event.player.posX, (int) event.player.posY, (int) event.player.posZ);
            // findBomb到玩家的距离从近到远排序
            findBomb.sort((o1, o2) -> {
                int squareD1 = (int) playerPos.distanceSquared(o1);
                int squareD2 = (int) playerPos.distanceSquared(o2);
                return Integer.compare(squareD1, squareD2);
            });
            this.findBomb = findBomb;
        } else if (needRender && !findBomb.isEmpty()) {
            Vector3i closest = findBomb.get(0);
            renderBlock(closest);
        }
        if (needRender) {
            if (System.nanoTime() - lastUse > 5000000000L) {
                needRender = false;
                findBomb.clear();
                findBombMap.get(event.player.getUniqueID()).clear();
            }
        }
    }

    public void renderBlock(Vector3i pos) {
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glPushMatrix();
        glLineWidth(Config.renderLineWidth);
        Vector3d translate = new Vector3d(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
        glTranslated(translate.x, translate.y + 0.05f, translate.z);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_LIGHTING);
        glEnable(GL_BLEND);
        glBegin(GL_LINES);
        glColor3f(1, 0.5f, 0.5f);
        glVertex3f(1, 1, 1); glVertex3f(1, 1, 0);
        glVertex3f(1, 1, 0); glVertex3f(1, 0, 0);
        glVertex3f(1, 0, 0); glVertex3f(1, 0, 1);
        glVertex3f(1, 0, 1); glVertex3f(1, 1, 1);

        glVertex3f(0, 0, 1); glVertex3f(0, 0, 0);
        glVertex3f(0, 0, 0); glVertex3f(0, 1, 0);
        glVertex3f(0, 1, 0); glVertex3f(0, 1, 1);
        glVertex3f(0, 1, 1); glVertex3f(0, 0, 1);

        glVertex3f(1, 0, 1); glVertex3f(0, 0, 1);
        glVertex3f(0, 1, 1); glVertex3f(1, 1, 1);
        glVertex3f(0, 0, 0); glVertex3f(1, 0, 0);
        glVertex3f(1, 1, 0); glVertex3f(0, 1, 0);
        glEnd();
        glPopMatrix();
        glPopAttrib();
    }

    /*public static class Field {
        public String type;
        public Vector3i pos;

        public Field(String type, Vector3i pos) {
            this.type = type;
            this.pos = pos;
        }
        public String toString() {
            return type + ": " + "(" + pos.x + ", " + pos.y + ", " + pos.z + ")";
        }
    }*/

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    public void unregister() {
        MinecraftForge.EVENT_BUS.unregister(this);
        FMLCommonHandler.instance().bus().unregister(this);
    }
}
