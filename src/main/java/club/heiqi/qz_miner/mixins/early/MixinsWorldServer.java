package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.util.BroadCastMessage;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;

@Mixin(WorldServer.class)
public class MixinsWorldServer {
    @Unique
    private MinecraftServer mcServer;

    @Inject(
        method = "tickUpdates",
        at = @At("HEAD"),
        cancellable = true,
        remap = true
    )
    public void qz_miner$tickUpdates(boolean p_72955_1_, CallbackInfoReturnable<Boolean> ci) {
        ci.cancel();
        int i = ((WorldServer)((Object)this)).pendingTickListEntriesTreeSet.size();
        boolean clearAllNextTick = false;
        if (i != ((WorldServer)((Object)this)).pendingTickListEntriesHashSet.size()) {
            if (Config.tickOutSyncCrash) {
                throw new IllegalStateException("TickNextTick list out of synch");
            }
            BroadCastMessage.broadCastMessage("qz_Miner已阻止计划刻不同步导致的崩溃；可在配置中关闭此功能[tickOutSyncCrash]");
            clearAllNextTick = true;
        }
        {
            ((WorldServer)((Object)this)).theProfiler.startSection("cleaning");
            NextTickListEntry nextticklistentry;
            // 限制更新次数 - 使用配置来控制
            if (i > Config.tickUpdateCount) i = Config.tickUpdateCount;
            for (int j = 0; j < i; ++j)
            {
                nextticklistentry = (NextTickListEntry)((WorldServer)((Object)this)).pendingTickListEntriesTreeSet.first();

                if (!p_72955_1_ && nextticklistentry.scheduledTime > ((WorldServer)((Object)this)).worldInfo.getWorldTotalTime())
                {
                    break;
                }

                ((WorldServer)((Object)this)).pendingTickListEntriesTreeSet.remove(nextticklistentry);
                ((WorldServer)((Object)this)).pendingTickListEntriesHashSet.remove(nextticklistentry);
                ((WorldServer)((Object)this)).pendingTickListEntriesThisTick.add(nextticklistentry);
            }

            ((WorldServer)((Object)this)).theProfiler.endSection();
            ((WorldServer)((Object)this)).theProfiler.startSection("ticking");
            Iterator iterator = ((WorldServer)((Object)this)).pendingTickListEntriesThisTick.iterator();

            while (iterator.hasNext()) {
                nextticklistentry = (NextTickListEntry)iterator.next();
                iterator.remove();
                //Keeping here as a note for future when it may be restored.
                //boolean isForced = getPersistentChunks().containsKey(new ChunkCoordIntPair(nextticklistentry.xCoord >> 4, nextticklistentry.zCoord >> 4));
                //byte b0 = isForced ? 0 : 8;
                byte b0 = 0;

                if (((WorldServer)((Object)this)).checkChunksExist(nextticklistentry.xCoord - b0, nextticklistentry.yCoord - b0, nextticklistentry.zCoord - b0, nextticklistentry.xCoord + b0, nextticklistentry.yCoord + b0, nextticklistentry.zCoord + b0))
                {
                    Block block = ((WorldServer)((Object)this)).getBlock(nextticklistentry.xCoord, nextticklistentry.yCoord, nextticklistentry.zCoord);

                    if (block.getMaterial() != Material.air && Block.isEqualTo(block, nextticklistentry.func_151351_a()))
                    {
                        try
                        {
                            block.updateTick(((WorldServer)((Object)this)), nextticklistentry.xCoord, nextticklistentry.yCoord, nextticklistentry.zCoord, ((WorldServer)((Object)this)).rand);
                        }
                        catch (Throwable throwable1)
                        {
                            CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception while ticking a block");
                            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being ticked");
                            int k;

                            try
                            {
                                k = ((WorldServer)((Object)this)).getBlockMetadata(nextticklistentry.xCoord, nextticklistentry.yCoord, nextticklistentry.zCoord);
                            }
                            catch (Throwable throwable)
                            {
                                k = -1;
                            }

                            CrashReportCategory.func_147153_a(crashreportcategory, nextticklistentry.xCoord, nextticklistentry.yCoord, nextticklistentry.zCoord, block, k);
                            throw new ReportedException(crashreport);
                        }
                    }
                }
                else
                {
                    ((WorldServer)((Object)this)).scheduleBlockUpdate(nextticklistentry.xCoord, nextticklistentry.yCoord, nextticklistentry.zCoord, nextticklistentry.func_151351_a(), 0);
                }
            }

            ((WorldServer)((Object)this)).theProfiler.endSection();
            ((WorldServer)((Object)this)).pendingTickListEntriesThisTick.clear();
            boolean b = !((WorldServer)((Object)this)).pendingTickListEntriesTreeSet.isEmpty();
            if (clearAllNextTick) {
                ((WorldServer)((Object)this)).pendingTickListEntriesTreeSet.clear();
                ((WorldServer)((Object)this)).pendingTickListEntriesHashSet.clear();
            }
            ci.setReturnValue(b);
        }
    }
}
