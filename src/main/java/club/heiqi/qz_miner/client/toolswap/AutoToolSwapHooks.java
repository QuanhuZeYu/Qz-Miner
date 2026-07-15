package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;

/** client-only Mixin 的静态路由；网络线程只捕获原始字段与连接 identity。 */
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

    /** 同步出包钩子；完整 intent 判断由 adapter 执行。 */
    public static void onClickWindowPacket(
            Object handler, int windowId, int slot, int button, int mode, int actionNumber) {
        AutoToolSwapClientAdapter current = adapter;
        if (current != null) {
            current.onClickWindowPacket(handler, windowId, slot, button, mode, actionNumber);
        }
    }

    /** S32 vanilla RETURN 后路由。 */
    public static void onConfirmTransaction(
            Object handler, final int windowId, final int actionNumber, final boolean accepted) {
        final AutoToolSwapClientAdapter current = adapter;
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(handler);
        dispatch(token, new Runnable() {
            @Override
            public void run() {
                current.onTransactionAck(token, windowId, actionNumber, accepted);
            }
        }, current);
    }

    /** S2F vanilla RETURN 后路由。 */
    public static void onSetSlot(Object handler, final int windowId, final int slot) {
        final AutoToolSwapClientAdapter current = adapter;
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(handler);
        dispatch(token, new Runnable() {
            @Override
            public void run() {
                current.onSetSlot(token, windowId, slot);
            }
        }, current);
    }

    /** S30 vanilla RETURN 后路由。 */
    public static void onWindowItems(Object handler, final int windowId) {
        final AutoToolSwapClientAdapter current = adapter;
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(handler);
        dispatch(token, new Runnable() {
            @Override
            public void run() {
                current.onWindowItems(token, windowId);
            }
        }, current);
    }

    private static void dispatch(
            final ClientConnectionLifecycle.Token token, final Runnable action,
            AutoToolSwapClientAdapter capturedAdapter) {
        if (capturedAdapter == null || token == null || !token.isWorldActive()) {
            return;
        }
        ClientMainThreadDispatcher.tryRun(new Runnable() {
            @Override
            public void run() {
                if (ClientConnectionLifecycle.isWorldCurrentAndActive(token)) {
                    action.run();
                }
            }
        });
    }
}
