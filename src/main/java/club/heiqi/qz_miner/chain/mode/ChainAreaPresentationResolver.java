package club.heiqi.qz_miner.chain.mode;

/**
 * 区域展示尺寸解析器。
 */
public interface ChainAreaPresentationResolver {

    /**
     * 计算 HUD 应显示的区域尺寸。
     *
     * @param radius 服务端同步半径
     * @param subMode 当前子模式
     * @return 长宽高
     */
    int[] resolveDimensions(int radius, ChainSubMode subMode);
}
