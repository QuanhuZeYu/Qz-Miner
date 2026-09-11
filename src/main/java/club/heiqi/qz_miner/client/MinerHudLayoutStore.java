package club.heiqi.qz_miner.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import club.heiqi.config.AtomicFileWrites;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.hud.api.HudLayoutStore;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * HUD 布局持久化端口实现（task-12，对齐 task-11 冻结规格第 8 节）。
 *
 * <p><b>纯文本 IO</b>：只把文件整串读出、把 UILib 给出的整串原子写回。文本格式、schemaVersion
 * 判定、锚点/百分比换算与损坏降级全部归 Qz-UILib；本类不解析内容、不做坐标数学、不引用布局
 * 或缩放类型。</p>
 *
 * <p><b>异常契约</b>：{@link #load()} 与 {@link #save(String)} 均不抛异常——读取失败按「无数据」
 * 返回空串，写入失败只告警并保留原文件（原子写先写临时文件再替换）。</p>
 *
 * <p><b>线程</b>：客户端主线程（与 HUD 注册、编辑提交同线程）。</p>
 */
@SideOnly(Side.CLIENT)
public final class MinerHudLayoutStore implements HudLayoutStore {

    private static final Logger LOG = LogManager.getLogger("QzMinerHudLayout");

    /** 布局文件名：与权威配置 qz_miner.yaml 同目录；格式由 UILib 冻结，宿主不手写不解析。 */
    static final String FILE_NAME = "qz_miner-hud-layout.txt";

    private final File file;

    /**
     * 以 config 目录构造。
     *
     * @param configDir Forge 配置目录（不可为 null）
     */
    public MinerHudLayoutStore(File configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir must not be null");
        }
        this.file = new File(configDir, FILE_NAME);
    }

    /**
     * 解析配置目录：优先权威 YAML 同级目录，其次配置路径父目录，最后相对 {@code config/}。
     *
     * <p>权威 YAML 由 {@code CommonProxy.preInit} 初始化，早于 {@code ClientProxy.init}，
     * 故正常路径下第一分支必然命中。</p>
     *
     * @return 配置目录（可能不存在，落盘时由原子写自动建目录）
     */
    public static File resolveConfigDir() {
        File yaml = ConfigBootstrap.yamlFile();
        if (yaml != null && yaml.getParentFile() != null) {
            return yaml.getParentFile();
        }
        String path = club.heiqi.qz_miner.Config.getConfigPath();
        if (path != null && !path.isEmpty()) {
            File parent = new File(path).getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        return new File("config");
    }

    /**
     * 读取布局原文。
     *
     * @return 文件原文（原样，不裁剪）；文件不存在或不可读返回空串（不抛）
     */
    @Override
    public String load() {
        try {
            if (!file.isFile()) {
                return "";
            }
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOG.warn("HUD 布局读取失败，按无持久化数据继续: {}", file.getAbsolutePath(), failure);
            return "";
        } catch (RuntimeException failure) {
            LOG.warn("HUD 布局读取异常，按无持久化数据继续: {}", file.getAbsolutePath(), failure);
            return "";
        }
    }

    /**
     * 原子写入布局原文（UILib 在提交编辑 / 缩放变更合并后调用）。
     *
     * <p>写入失败只告警：原子写先写同目录临时文件再替换，失败时旧文件保持原字节。</p>
     *
     * @param text 完整文本（null 视为空文本）
     */
    @Override
    public void save(String text) {
        try {
            AtomicFileWrites.writeUtf8Atomically(file, text == null ? "" : text);
        } catch (IOException failure) {
            LOG.warn("HUD 布局写入失败，保留原文件: {}", file.getAbsolutePath(), failure);
        } catch (RuntimeException failure) {
            LOG.warn("HUD 布局写入异常，保留原文件: {}", file.getAbsolutePath(), failure);
        }
    }

    /** @return 目标文件（测试与诊断探针） */
    File file() {
        return file;
    }
}
