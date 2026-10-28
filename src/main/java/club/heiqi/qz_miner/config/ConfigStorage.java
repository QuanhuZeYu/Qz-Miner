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
        // 先在内存完成编码，再创建临时文件，保留当前权威文件直到原子替换。
        StringWriter encoded = new StringWriter();
        candidate.write(encoded);
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        boolean replaced = false;
        try {
            Files.write(temporary, encoded.toString().getBytes(StandardCharsets.UTF_8));
            // 原子替换失败向上传递，当前权威文件与已发布快照继续有效。
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }
}
