package club.heiqi.qz_miner.config;

import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * 将同一个已提交快照投递到分侧 dispatcher，并在任务执行时阻止陈旧发布。
 */
public final class ConfigSnapshotDispatch {

    private ConfigSnapshotDispatch() {
    }

    /** 任务投递边界。 */
    public interface Dispatcher {
        /** @param task 待投递任务 */
        void dispatch(Runnable task);
    }

    /** 快照发布动作。 */
    public interface Publication {
        /** @param snapshot 同步捕获的提交快照 */
        void publish(ValidatedSnapshot snapshot);
    }

    /**
     * 投递 client，并按需投递 server；所有任务闭包捕获同一 snapshot 引用。
     *
     * @param snapshot 已提交快照
     * @param clientDispatcher 客户端 dispatcher
     * @param clientPublication 客户端发布动作
     * @param publishServer 是否发布服务端 general
     * @param serverDispatcher 服务端 dispatcher
     * @param serverPublication 服务端发布动作
     *
     * <p>所有可预见参数错误在首次投递前拒绝。dispatcher 运行时异常按 best-effort 处理：异常向上抛出，
     * 已被前序 dispatcher 接受的任务无法回滚，尚未调用的后序 dispatcher 不再投递。</p>
     */
    public static void dispatch(
            final ValidatedSnapshot snapshot,
            Dispatcher clientDispatcher,
            final Publication clientPublication,
            boolean publishServer,
            Dispatcher serverDispatcher,
            final Publication serverPublication) {
        requireClient(snapshot, clientDispatcher, clientPublication);
        requireServerIfEnabled(publishServer, serverDispatcher, serverPublication);
        clientDispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                publishIfCurrent(snapshot, clientPublication);
            }
        });
        if (!publishServer) {
            return;
        }
        serverDispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                publishIfCurrent(snapshot, serverPublication);
            }
        });
    }

    private static void publishIfCurrent(
            final ValidatedSnapshot snapshot,
            final Publication publication) {
        ConfigBootstrap.publishIfCurrent(snapshot, new Runnable() {
            @Override
            public void run() {
                publication.publish(snapshot);
            }
        });
    }

    private static void requireClient(ValidatedSnapshot snapshot, Dispatcher dispatcher, Publication publication) {
        if (snapshot == null || dispatcher == null || publication == null) {
            throw new IllegalArgumentException("snapshot/client dispatcher/publication must not be null");
        }
    }

    private static void requireServerIfEnabled(
            boolean publishServer,
            Dispatcher dispatcher,
            Publication publication) {
        if (publishServer && (dispatcher == null || publication == null)) {
            throw new IllegalArgumentException("server dispatcher/publication must not be null");
        }
    }
}
