package club.heiqi.qz_miner.chain.planner;

import java.lang.reflect.Field;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.state.ChainRequest;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** 规划冻结 seed 到执行 session 的结构传播合同。 */
public class ChainPlanningInteractionSeedPropagationStructureTest {

    private static final String BRIDGE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java";

    /** shadow session 必须使用同一 BlockSeedSnapshot 三元组，不能重新读取 world。 */
    @Test
    public void bridgeCopiesFrozenSnapshotIntoShadowSession() {
        String onPlanStarted = onPlanStartedBody();

        String sessionArguments = JavaSourceSlices.callArguments(onPlanStarted, "new ChainSession(",
                "影子 session 构造");
        JavaSourceSlices.assertBefore(sessionArguments, "seedSnapshot.getSampleBlock()",
                "seedSnapshot.getSampleMeta()", "session 实参顺序 block→meta");
        JavaSourceSlices.assertBefore(sessionArguments, "seedSnapshot.getSampleMeta()",
                "seedSnapshot.getSampleTileIdentity()", "session 实参顺序 meta→token");
        JavaSourceSlices.assertBefore(onPlanStarted, "new ChainSession(", "createForServer(",
                "冻结 seed 必须在 runtime/worker 装配前进入 session");

        int sessionConstruction = onPlanStarted.indexOf("new ChainSession(");
        int runtimeCreation = onPlanStarted.indexOf("createForServer(", sessionConstruction);
        String construction = onPlanStarted.substring(sessionConstruction, runtimeCreation);
        JavaSourceSlices.assertAbsent(construction, "worldObj.get", "构造执行 session 时不得回读 world");
    }

    /** request/session 不得持有 live world、TileEntity、NBT 或 Class。 */
    @Test
    public void requestAndSessionKeepOnlyFrozenValues() {
        assertNoLiveGameStateFields(ChainRequest.class);
        assertNoLiveGameStateFields(ChainSession.class);
    }

    private static String onPlanStartedBody() {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(BRIDGE_PATH),
                "private void onPlanStarted(", "ChainPlanningEventBridge.onPlanStarted");
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
}
