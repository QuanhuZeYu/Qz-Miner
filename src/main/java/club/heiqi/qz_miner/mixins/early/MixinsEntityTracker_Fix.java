package club.heiqi.qz_miner.mixins.early;

import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(EntityTracker.class)
public abstract class MixinsEntityTracker_Fix {
    @Unique
    private final Object lock = new Object();

    /**
     * 将 trackedEntities 替换为线程安全的 ConcurrentHashMap KeySet
     */
    @Inject(
        method = "<init>", // 构造方法的注入点
        at = @At("RETURN")  // 在构造方法执行后执行
    )
    public void replaceTrackedEntities(WorldServer world, CallbackInfo ci) {
        EntityTracker tracker = (EntityTracker) (Object) this;
        // 替换为线程安全的 Set
        tracker.trackedEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    @Inject(
        method = "updateTrackedEntities",
        at = @At("HEAD"),
        cancellable = true,
        remap = true
    )
    public void qz_miner$fix$updateTrackedEntities(CallbackInfo ci) {
        EntityTracker tracker = (EntityTracker) (Object) this;

        // 1. 直接遍历线程安全的 trackedEntities
        List<EntityPlayerMP> playersToUpdate = new ArrayList<>();
        for (EntityTrackerEntry entry : (Set<EntityTrackerEntry>) tracker.trackedEntities) {
            entry.sendLocationToAllClients(tracker.theWorld.playerEntities);
            if (entry.playerEntitiesUpdated && entry.myEntity instanceof EntityPlayerMP) {
                playersToUpdate.add((EntityPlayerMP) entry.myEntity);
            }
        }

        // 2. 内层循环复用原集合（ConcurrentHashMap 的迭代器是弱一致性）
        for (EntityPlayerMP player : playersToUpdate) {
            for (EntityTrackerEntry innerEntry : (Set<EntityTrackerEntry>) tracker.trackedEntities) {
                if (innerEntry.myEntity != player) {
                    innerEntry.tryStartWachingThis(player);
                }
            }
        }

        ci.cancel();
    }
}
