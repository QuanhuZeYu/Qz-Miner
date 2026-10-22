package club.heiqi.qz_miner.config;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import club.heiqi.uilib.ui.config.ConfigDocument;

/** Miner 的文件事务边界；UILib write(Path) 只有编码保证，原子替换由业务承担。 */
final class ConfigStorage {
    private ConfigStorage() { }

    static void write(Path target, ConfigDocument candidate) throws IOException {
        // 编码可能抛错，必须先在内存中完成，再创建临时文件；禁止截断当前权威文件。
        StringWriter encoded = new StringWriter();
        candidate.write(encoded);
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        boolean replaced = false;
        try {
            Files.write(temporary, encoded.toString().getBytes(StandardCharsets.UTF_8));
            // 不降级为非原子 move；不支持原子替换也属于失败，保留当前权威和已发布快照。
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }
}
