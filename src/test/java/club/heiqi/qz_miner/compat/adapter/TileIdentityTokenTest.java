package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** {@link TileIdentityToken} 纯值合同与身份比较矩阵。 */
public class TileIdentityTokenTest {

    /** token 只能持有 final 纯值字段，禁止夹带游戏对象、反射类型或 NBT。 */
    @Test
    public void tokenIsImmutableAndContainsNoRuntimeObjects() {
        for (Field field : TileIdentityToken.class.getDeclaredFields()) {
            Assert.assertTrue("token 字段必须 final: " + field.getName(), Modifier.isFinal(field.getModifiers()));
            Class<?> type = field.getType();
            Assert.assertNotEquals(TileEntity.class, type);
            Assert.assertNotEquals(World.class, type);
            Assert.assertNotEquals(Block.class, type);
            Assert.assertNotEquals(NBTBase.class, type);
            Assert.assertNotEquals(Class.class, type);
        }
    }

    /** 默认矩阵：ABSENT 仅与 ABSENT 等价。 */
    @Test
    public void absentOnlyMatchesAbsent() {
        Assert.assertTrue(CompatAdapters.matchesTileIdentity(
                TileIdentityToken.absent(), TileIdentityToken.absent()));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                TileIdentityToken.absent(), TileIdentityToken.present("field", "fixture", "7")));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                TileIdentityToken.present("field", "fixture", "7"), TileIdentityToken.absent()));
    }

    /** 默认矩阵：PRESENT 必须 strategy/type/key 三元组全部相同。 */
    @Test
    public void presentRequiresSameStrategyTypeAndKey() {
        TileIdentityToken seed = TileIdentityToken.present("field", "fixture", "7");
        Assert.assertTrue(CompatAdapters.matchesTileIdentity(
                seed, TileIdentityToken.present("field", "fixture", "7")));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                seed, TileIdentityToken.present("other", "fixture", "7")));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                seed, TileIdentityToken.present("field", "other.Fixture", "7")));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                seed, TileIdentityToken.present("field", "fixture", "8")));
    }

    /** 默认矩阵：任一 UNRESOLVED 都严格拒绝，不能充当通配符。 */
    @Test
    public void unresolvedAlwaysRejects() {
        TileIdentityToken unresolved = TileIdentityToken.unresolved();
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(unresolved, unresolved));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(unresolved, TileIdentityToken.absent()));
        Assert.assertFalse(CompatAdapters.matchesTileIdentity(
                TileIdentityToken.present("field", "fixture", "7"), unresolved));
    }

    /** 安全摘要不得输出可能承载 owner/NBT 等敏感业务值的 identity key。 */
    @Test
    public void stringSummaryDoesNotExposeIdentityKey() {
        TileIdentityToken token = TileIdentityToken.present("field", "fixture", "secret-owner-key");
        Assert.assertFalse(token.toString().contains("secret-owner-key"));
    }
}
