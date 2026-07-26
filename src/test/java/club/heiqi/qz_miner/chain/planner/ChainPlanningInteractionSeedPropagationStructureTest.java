package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.state.ChainRequest;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 规划冻结 seed 到执行 session 的结构传播合同。 */
public class ChainPlanningInteractionSeedPropagationStructureTest {

    /** shadow session 必须使用同一 BlockSeedSnapshot 三元组，不能重新读取 world。 */
    @Test
    public void bridgeCopiesFrozenSnapshotIntoShadowSession() throws Exception {
        String source = read(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java");
        int sessionConstruction = source.indexOf("final ChainSession shadowSession = new ChainSession(");
        int seedBlock = source.indexOf("seedSnapshot.getSampleBlock()", sessionConstruction);
        int seedMeta = source.indexOf("seedSnapshot.getSampleMeta()", seedBlock);
        int seedToken = source.indexOf("seedSnapshot.getSampleTileIdentity()", seedMeta);
        int runtimeCreation = source.indexOf("ChainPlanningRuntimeFactory.createForServer(", seedToken);

        Assert.assertTrue(sessionConstruction >= 0);
        Assert.assertTrue(seedBlock > sessionConstruction && seedMeta > seedBlock && seedToken > seedMeta);
        Assert.assertTrue("seed 必须在 runtime/worker 装配前进入 session", runtimeCreation > seedToken);
        String construction = source.substring(sessionConstruction, runtimeCreation);
        Assert.assertFalse("构造执行 session 时不得回读 world", construction.contains("worldObj.get"));
    }

    /** request/session 不得持有 live world、TileEntity、NBT 或 Class。 */
    @Test
    public void requestAndSessionKeepOnlyFrozenValues() {
        assertNoLiveGameStateFields(ChainRequest.class);
        assertNoLiveGameStateFields(ChainSession.class);
    }

    private static void assertNoLiveGameStateFields(Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            Class<?> type = field.getType();
            Assert.assertFalse(owner.getSimpleName() + " 不得持有 World", World.class.isAssignableFrom(type));
            Assert.assertFalse(owner.getSimpleName() + " 不得持有 TileEntity",
                    TileEntity.class.isAssignableFrom(type));
            Assert.assertFalse(owner.getSimpleName() + " 不得持有 NBT",
                    NBTTagCompound.class.isAssignableFrom(type));
            Assert.assertNotEquals(owner.getSimpleName() + " 不得持有 Class", Class.class, type);
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
