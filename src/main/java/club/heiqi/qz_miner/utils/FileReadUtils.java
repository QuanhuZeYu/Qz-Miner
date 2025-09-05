package club.heiqi.qz_miner.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 读取jar包内的文件工具类
 */
public class FileReadUtils {
    public static Logger LOG = LogManager.getLogger();

    /**
     * 读取jar包内的文本文件
     * @param path 文件路径（相对于classpath）
     * @return 文件内容字符串，读取失败时返回空字符串
     */
    public static String readText(String path) {
        // 确保路径以"/"开头，表示从classpath根目录读取
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        StringBuilder content = new StringBuilder();
        // 使用try-with-resources确保流正确关闭
        try (InputStream is = FileReadUtils.class.getResourceAsStream(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append(System.lineSeparator());
            }
        } catch (IOException | NullPointerException e) {
            LOG.error("读取文件失败: " + path, e);
            return "";
        }
        return content.toString();
    }
}
