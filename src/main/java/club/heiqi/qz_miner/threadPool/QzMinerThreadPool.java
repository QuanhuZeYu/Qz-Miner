package club.heiqi.qz_miner.threadPool;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class QzMinerThreadPool {
    public static final int THREAD_NUM = 4;
    public static ThreadFactory qzMinerThreadFactory = new ThreadFactoryBuilder()
        .setNameFormat("QzMinerThreadPool-%d")
        .setDaemon(true) // 守护线程
        .build();
    public static ThreadPoolExecutor pool = new ThreadPoolExecutor(
        THREAD_NUM,
        6,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4),
        qzMinerThreadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
}
