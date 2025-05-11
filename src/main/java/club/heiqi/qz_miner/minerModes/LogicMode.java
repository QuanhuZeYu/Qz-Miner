package club.heiqi.qz_miner.minerModes;

public enum LogicMode {
    CLIENT("client"),
    SERVER("server"),
    ;
    final String mode;
    LogicMode(String mode) {
        this.mode = mode;
    }
}
