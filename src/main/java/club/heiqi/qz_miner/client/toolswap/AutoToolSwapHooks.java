package club.heiqi.qz_miner.client.toolswap;

/** client-only Mixin 的静态路由。 */
public final class AutoToolSwapHooks {

    private static volatile AutoToolSwapClientAdapter adapter;

    private AutoToolSwapHooks() {
    }

    /** 安装唯一长寿命 adapter。 */
    public static void install(AutoToolSwapClientAdapter value) {
        adapter = value;
    }

    /** PlayerControllerMP 成功返回钩子。 */
    public static void onLocalBlockDestroyed() {
        AutoToolSwapClientAdapter current = adapter;
        if (current != null) {
            current.onLocalBlockDestroyed();
        }
    }

}
