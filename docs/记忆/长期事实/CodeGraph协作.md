# CodeGraph MCP 协作

本文件记录本仓库在 opencode 中使用 CodeGraph MCP 时的长期协作约定。

## 当前接入方式

- 仓库已提供项目级 opencode 配置：`.opencode/opencode.json`
- 项目级配置显式注册 `codegraph` MCP，Windows 下通过 `cmd.exe /d /s /c` 启动 `npx -y @astudioplus/codegraph-mcp --graph-only`
- `@astudioplus/codegraph-mcp` 的 npm 启动器会自行进入 MCP 模式，配置中不要额外传 `--mcp`，否则底层会收到重复参数并启动失败
- 协作者只要从仓库内启动 opencode，即可按项目配置加载 CodeGraph；个人全局安装只作为本机加速，不是协作前提
- 项目级配置排除 `.git`、`.gradle`、`build`、`run`、`node_modules` 等目录，避免索引构建产物、运行目录和依赖缓存
- 项目级配置设置 `CODEGRAPH_TELEMETRY=off`，不发送 CodeGraph 匿名遥测
- opencode 配置变更后需要退出并重启 opencode 才会加载新的 MCP

## 使用定位

- `docs/记忆/` 继续承载稳定协作记忆：当前态、长期事实、决策和交接
- CodeGraph 负责动态代码关系：符号搜索、调用链、依赖图、影响面、相关测试和 PR 上下文
- 当前项目配置使用 `--graph-only`，优先保证符号、调用链、依赖图和影响分析稳定启动；语义 embedding 类能力不作为默认协作前提
- 源码、Gradle 配置和测试结果仍是最终事实来源，CodeGraph 查询结果不能替代 `Read`、定向测试和编译验证
- 不把 CodeGraph 的大段查询输出写入记忆文档；只把对后续协作仍有价值的稳定结论写回对应层级

## 推荐读取流程

1. 先读 `docs/AI记忆文档.md` 与 `docs/记忆/README.md`，确认任务所属记忆层
2. 再按任务读取 `当前态/`、`长期事实/`、`决策/`、错误记录或审查记录
3. 涉及具体类、方法、调用链、依赖面、影响面时，优先使用 `codegraph` MCP 查询结构关系
4. 对 CodeGraph 返回的关键文件和符号，用 `Read`、`Grep`、`Glob` 或 LSP 再确认源码现状
5. 修改完成后按 `docs/记忆/长期事实/稳定命令.md` 运行定向验证，不以 CodeGraph 静态分析结果代替测试

## 适合使用 CodeGraph 的场景

- 查找某个类、方法、接口或字段的定义与相关符号
- 分析调用方、被调用方、模块依赖和修改影响面
- 在重构前确认可能受影响的测试、入口和宿主调用链
- 做 PR 或阶段变更的上下文梳理，辅助发现遗漏测试和文档影响
- 在大文件或跨包逻辑中先缩小范围，再用源码读取做最终判断

## 不适合写入 CodeGraph 记忆的内容

- API key、令牌、账号、私有地址和其他敏感信息
- 临时调试日志、一次性试验结果和纯会话流水账
- 已经应该进入 `docs/使用文档/`、`docs/开发者文档/errors/`、`docs/开发者文档/reviews/` 或 `docs/记忆/决策/` 的稳定结论
- 未经源码或测试确认的推断性结论

## 写回规则

- CodeGraph 查询发现目录、入口、命令或长期边界变化时，写回 `docs/记忆/长期事实/`
- CodeGraph 查询支持了关键方案取舍时，写入 `docs/记忆/决策/`
- CodeGraph 查询暴露可复现错误或审查结论时，写入 `docs/开发者文档/errors/` 或 `docs/开发者文档/reviews/`
- CodeGraph 查询只用于临时定位时，不新增记忆文档

## 降级策略

- MCP 未加载、索引过期或查询结果明显不完整时，立即回退到 `Glob`、`Grep`、`Read` 和源码测试
- 大规模移动、重命名或生成文件变化后，优先重新索引工作区或重启 opencode，再继续依赖 CodeGraph 结果
- 如果 CodeGraph 与源码读取结果冲突，以源码读取和测试验证为准
