package club.heiqi.qz_miner.client.debug;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.Mod_Main;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.joml.Vector3d;
import org.joml.Vector3i;
import ru.timeconqueror.lootgames.api.block.GameBlock;
import ru.timeconqueror.lootgames.api.block.GameMasterBlock;
import ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock;
import ru.timeconqueror.lootgames.api.util.Pos2i;
import ru.timeconqueror.lootgames.common.block.tile.MSMasterTile;
import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;
import ru.timeconqueror.lootgames.minigame.minesweeper.MSBoard;
import ru.timeconqueror.lootgames.minigame.minesweeper.Type;
import ru.timeconqueror.lootgames.utils.future.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static club.heiqi.qz_miner.client.renderSelect.RenderSelect.camPos;
import static club.heiqi.qz_miner.client.renderSelect.RenderSelect.mc;
import static org.lwjgl.opengl.GL11.*;

public class Debug {
    public Set<Vector3i> findBomb = new HashSet<>();
    public boolean needRender = false;
    public long timer;

    @SubscribeEvent
    public void debug(PlayerInteractEvent event) {
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            Vector3i pos = new Vector3i(event.x, event.y, event.z);
            Block block = event.world.getBlock(pos.x, pos.y, pos.z);
            if (block instanceof GameBlock) {
                BlockPos master = SmartSubordinateBlock.getMasterPos(event.world, new BlockPos(pos.x, pos.y, pos.z));
                pos.set(master.getX(), master.getY(), master.getZ());
                Vector3i masterPos = new Vector3i(pos);
                TileEntity SMT = event.world.getTileEntity(pos.x, pos.y, pos.z);
                if (SMT instanceof MSMasterTile MSMTile) {
                    GameMineSweeper game = MSMTile.getGame();
                    MSBoard board = game.getBoard();
                    if (board == null) return;
                    Block curB = event.world.getBlock(pos.x, pos.y, pos.z);
                    int length = 0;
                    while (curB instanceof GameBlock) {
                        length += 1;
                        pos.add(0, 0, 1);
                        curB = event.world.getBlock(pos.x, pos.y, pos.z);
                    }
                    Vector3i fieldStart = new Vector3i(masterPos.x + (length - board.size())  / 2, masterPos.y, masterPos.z + (length - board.size()) / 2);
                    ArrayList<ArrayList<Field>> record = new ArrayList<>();
                    findBomb.clear();
                    for (int x = 0; x < board.size(); x++) {
                        ArrayList<Field> line = new ArrayList<>();
                        for (int z = 0; z < board.size(); z++) {
                            Vector3i fieldPos = new Vector3i(fieldStart.x + x, fieldStart.y, fieldStart.z + z);
                            Pos2i fieldPos2i = new Pos2i(x, z);
                            String type;
                            try {
                                type = board.getType(fieldPos2i).getId() == -1 ? "BOMB" : "EMPTY";
                            } catch (Exception e) {
                                return;
                            }
                            if (type.equals("BOMB")) {
                                findBomb.add(fieldPos);
                            }
                            Field field = new Field(type, fieldPos);
                            if (board.hasFieldOn(fieldPos2i)) {
                                line.add(field);
                            }
                        }
                        record.add(line);
                    }
                    //MY_LOG.LOG.debug("Record");
                    needRender = true;
                    timer = System.nanoTime();
                }
            }
        }
    }

    @SubscribeEvent
    public void renderBomb(DrawBlockHighlightEvent event) {
        if (needRender) {
            ArrayList<Vector3i> findBomb = new ArrayList<>(Debug.this.findBomb);
            for (Vector3i pos : findBomb) {
                renderBlock(pos);
            }
            if (System.nanoTime() - timer > 10000000000L) {
                needRender = false;
            }
        }
    }

    @SideOnly(Side.CLIENT)
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

    public static class Field {
        public String type;
        public Vector3i pos;

        public Field(String type, Vector3i pos) {
            this.type = type;
            this.pos = pos;
        }
        public String toString() {
            return type + ": " + "(" + pos.x + ", " + pos.y + ", " + pos.z + ")";
        }
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }
}
