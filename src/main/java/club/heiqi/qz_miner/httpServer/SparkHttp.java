package club.heiqi.qz_miner.httpServer;

import club.heiqi.qz_miner.statueStorage.PlayerTracer;
import com.google.gson.Gson;
import cpw.mods.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import spark.Spark;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认使用MC服务器端口+1 25565 -> 25566 作为http端口
 */
public class SparkHttp {
    public static Logger LOG = LogManager.getLogger();
    public static File staticFile;

    public SparkHttp() {
        LOG.info("正在创建http后端");
        File mcDir = new File(System.getProperty("user.dir"));
        staticFile =new File(mcDir, "Qz/http");
        try {
            int mcPort = FMLCommonHandler.instance().getMinecraftServerInstance().getPort();
            Spark.port(mcPort + 1);
            Spark.staticFiles.externalLocation(staticFile.getAbsolutePath());
            // 访问根路径时返回html页面
            Spark.get("/", (req,res) -> {
                res.type("text/html");
                return new String(Files.readAllBytes(new File(staticFile, "jsHtml.html").toPath()));
            });

            // 自动处理html中的js依赖
            Spark.get("/src/*", (req, res) -> {
                res.type("text/javascript");
                // 获取请求路径中/src/后面的部分
                String path = req.splat()[0];
                File jsFile = new File(staticFile, "src/" + path);

                // 检查文件是否存在且是.js文件
                if (jsFile.exists() && jsFile.isFile() && jsFile.getName().endsWith(".js")) {
                    try {
                        return new String(Files.readAllBytes(jsFile.toPath()));
                    } catch (IOException e) {
                        res.status(500);
                        return "Error reading file: " + e.getMessage();
                    }
                } else {
                    res.status(404);
                    return "File not found or not a JavaScript file";
                }
            });

            // 自动处理css请求
            /*Spark.get("/css/*", (req, res) -> {
                res.type("text/css");
                // 获取请求路径中/css/后面的部分
                String path = req.splat()[0];
                File cssFile = new File(staticFile, "css/" + path);

                // 检查文件是否存在且是.css文件
                if (cssFile.exists() && cssFile.isFile() && cssFile.getName().endsWith(".css")) {
                    try {
                        return new String(Files.readAllBytes(cssFile.toPath()));
                    } catch (IOException e) {
                        res.status(500);
                        return "Error reading CSS file: " + e.getMessage()";
                    }
                } else {
                    res.status(404);
                    return "CSS file not found or not a CSS file";
                }
            });*/

            // 测试端口
            Spark.post("/qz/test", (req,res) -> {
                res.type("application/json");
                Map<String,String> json = new HashMap<>();
                json.put("test","你好世界");
                return new Gson().toJson(json);
            });

            // 访问日志记录
            Spark.post("/qz/playerInfo", (req,res) -> {
                res.type("application/json");
                Map<String, Map<String, Map<String, List<Map<String, Object>>>>> ret = PlayerTracer.GLOBAL_TRACE;
                return new Gson().toJson(ret);
            });

            // 获取MC服务器IP和端口号
            Spark.post("/qz/ServerInfo", (req,res) -> {
                res.type("application/json");
                Map ret = new HashMap();
                ret.put("ip","heiqi.club");
                ret.put("port",mcPort);
                return new Gson().toJson(ret);
            });
        } catch (Throwable e) {
            LOG.error("创建后端服务失败: {}",e);
        }
    }
}
