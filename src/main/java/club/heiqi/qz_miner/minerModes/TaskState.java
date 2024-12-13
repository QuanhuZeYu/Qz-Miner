package club.heiqi.qz_miner.minerModes;

public enum TaskState {
    IDLE,
    WAIT, // 用于再入循环
    RUNNING,
    STOP;
}
