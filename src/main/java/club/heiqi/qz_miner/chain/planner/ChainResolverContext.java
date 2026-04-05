package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 模式解析上下文。
 */
public final class ChainResolverContext {

    private final EntityPlayer player;
    private final ChainSession session;
    private final ChainSearchContext searchContext;

    public ChainResolverContext(EntityPlayer player, ChainSession session, ChainSearchContext searchContext) {
        this.player = player;
        this.session = session;
        this.searchContext = searchContext;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public ChainSession getSession() {
        return session;
    }

    public ChainSearchContext getSearchContext() {
        return searchContext;
    }
}
