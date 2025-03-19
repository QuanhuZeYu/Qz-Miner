package club.heiqi.qz_miner.mixins.early;

import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Block.class)
public abstract class MixinsBlock {
    /*@Unique
    public Logger LOG = LogManager.getLogger();

    @Inject(
        method = "dropBlockAsItem(Lnet/minecraft/world/World;IIILnet/minecraft/item/ItemStack;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = true
    )
    public void qz_miner$dropBlockAsItem(World worldIn, int x, int y, int z, ItemStack itemIn, CallbackInfo ci) {
        if (!worldIn.isRemote &&
            worldIn.getGameRules().getGameRuleBooleanValue("doTileDrops") &&
            !worldIn.restoringBlockSnapshots) {

            EntityItem entityitem = new EntityItem(worldIn, x, y, z, itemIn);
            entityitem.delayBeforeCanPickup = 10;
            *//*LOG.info(entityitem.getEntityItem().getDisplayName());*//*
            Vector3i pos = new Vector3i(x, y, z);
            if (Config.dropItemToSelf) {
                // 原子化操作：初始化队列并添加实体
                GLOBAL_DROPS.computeIfAbsent(pos, k -> new ConcurrentLinkedQueue<>())
                    .offer(entityitem);
                GlobalDropCleaner.lastGlobalChangeTime = System.currentTimeMillis();
                ci.cancel(); // 阻止原版实体生成
            }
        }
    }

    @ModifyConstant(
        method = "harvestBlock",
        constant = @Constant(floatValue = 0.025F)
    )
    public float qz_miner$modifyExhaustion$harvestBlock(float original) {
        return Config.exhaustion;
    }*/
}
