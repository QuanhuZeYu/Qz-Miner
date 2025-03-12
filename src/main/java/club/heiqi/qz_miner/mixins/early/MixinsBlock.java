package club.heiqi.qz_miner.mixins.early;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static club.heiqi.qz_miner.minerModes.ModeManager.GLOBAL_DROPS;

@Mixin(Block.class)
public abstract class MixinsBlock {

    @Inject(
        method = "dropBlockAsItem(Lnet/minecraft/world/World;IIILnet/minecraft/item/ItemStack;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    public void qz_miner$dropBlockAsItem(World worldIn, int x, int y, int z, ItemStack itemIn, CallbackInfo ci) {
        if (!worldIn.isRemote &&
            worldIn.getGameRules().getGameRuleBooleanValue("doTileDrops") &&
            !worldIn.restoringBlockSnapshots) {
            // 创建实体并加入全局队列
            EntityItem entityitem = new EntityItem(worldIn, x, y, z, itemIn);
            entityitem.delayBeforeCanPickup = 10;
            Vector3i pos = new Vector3i(x, y, z);
            // 原子化操作：初始化队列并添加实体
            GLOBAL_DROPS.computeIfAbsent(pos, k -> new ConcurrentLinkedQueue<>())
                .offer(entityitem);
            System.out.println("已缓存掉落物: " + entityitem + " 于位置 " + pos);
            ci.cancel(); // 阻止原版实体生成
        }
        ci.cancel();
    }
}
