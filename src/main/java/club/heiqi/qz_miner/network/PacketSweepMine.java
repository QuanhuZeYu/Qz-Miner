package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.UUID;

public class PacketSweepMine implements IMessage {
    public ArrayList<Vector3i> mines = new ArrayList<>();

    public PacketSweepMine() {
    }

    public PacketSweepMine(ArrayList<Vector3i> mines) {
        this.mines = mines;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int length = buf.readInt();
        for (int i = 0; i < length; i++) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            mines.add(new Vector3i(x, y, z));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(mines.size());
        mines.forEach(pos -> {
            buf.writeInt(pos.x);
            buf.writeInt(pos.y);
            buf.writeInt(pos.z);
        });
    }

    public static class Handler implements IMessageHandler<PacketSweepMine, IMessage> {
        @Override
        public IMessage onMessage(PacketSweepMine message, MessageContext ctx) {
            Minecraft mc = ctx.getClientHandler().gameController;
            EntityClientPlayerMP player = mc.thePlayer;
            UUID uuid = player.getUniqueID();
//            RenderMines.findBombMap.get(uuid).clear();
//            RenderMines.findBombMap.get(uuid).addAll(message.mines);
            return null;
        }
    }
}
