package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;

import club.heiqi.qz_miner.MyMod;

/**
 * 全表枚举（过渡态 T-4，仅剩清单遍历 + 逐方块物化委托）。
 *
 * <p><b>删除条件（ADR §1.7 T-4）</b>：全部生产调用点消失（{@code ObjectGroupPickerRegistration}
 * 改挂惰性候选源）且测试改用分片入口。删除时机 = P2-B 收尾提交（{@code BlockRegistrySnapshot} +
 * {@link BlockVariantShardCache} 接管清单与分片之后）。</p>
 *
 * <p>物化方法体已迁至 {@link BlockVariantMaterializer}（无状态、可单测）；本类只保留
 * 「遍历注册表 + 失败限流」这一步，用于与惰性路径对拍，随后整体删除。</p>
 */
public final class BlockVariantEnumerator {

    private static final int MAX_FAILURE_LOGS = 8;

    private BlockVariantEnumerator() {
    }

    /**
     * 遍历当前客户端的 Block registry；不访问 world、worker 或 NEI。
     *
     * @return 全量候选（不可变）
     * @deprecated 过渡态 T-4：由 {@link BlockPickerCandidateSource} 的惰性分片路径替代，P2-B 收尾删除
     */
    @Deprecated
    public static List<BlockCandidate> enumerate() {
        List<BlockCandidate> result = new ArrayList<BlockCandidate>();
        int failures = 0;
        for (Object value : Block.blockRegistry) {
            if (!(value instanceof Block)) {
                continue;
            }
            Block block = (Block) value;
            String registry = null;
            try {
                Object key = Block.blockRegistry.getNameForObject(block);
                registry = key == null ? null : key.toString();
                if (!BlockRegistrySnapshot.isValidRegistry(registry)) {
                    continue;
                }
                result.add(BlockVariantMaterializer.materialize(registry, block));
            } catch (RuntimeException e) {
                if (failures++ < MAX_FAILURE_LOGS) {
                    MyMod.LOG.warn("Block picker degraded for {}", registry, e);
                }
                if (BlockRegistrySnapshot.isValidRegistry(registry)) {
                    result.add(BlockVariantMaterializer.placeholder(registry));
                }
            } catch (LinkageError e) {
                if (failures++ < MAX_FAILURE_LOGS) {
                    MyMod.LOG.warn("Block picker linkage degraded for {}", registry, e);
                }
                if (BlockRegistrySnapshot.isValidRegistry(registry)) {
                    result.add(BlockVariantMaterializer.placeholder(registry));
                }
            }
        }
        if (failures > MAX_FAILURE_LOGS) {
            MyMod.LOG.warn("Block picker suppressed {} additional enumeration failures", failures - MAX_FAILURE_LOGS);
        }
        return Collections.unmodifiableList(result);
    }
}
