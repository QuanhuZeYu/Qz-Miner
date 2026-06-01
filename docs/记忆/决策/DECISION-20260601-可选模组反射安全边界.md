# 决策：可选模组反射安全边界

## 背景

Issue `#235` 显示 Qz-Miner 5.0.16 在 GTNH 2.8.0 dedicated server 启动时崩溃。根因不是 LootGames 缺失，而是兼容层在服务端用 `Class#getMethod(...)` 解析 LootGames 方块类公开方法，触发 JVM 解析 `registerBlockIcons(IIconRegister)` 的客户端专属签名。

## 候选方案

- 在当前崩溃点外层捕获 `NoClassDefFoundError`，遇到异常后禁用 LootGames 扫雷兼容。
- 仅把 `getMethod(...)` 改为 `getDeclaredMethod(...)`，降低公开方法扫描范围。
- 建立统一可选模组反射边界，并调整 LootGames 扫雷兼容，不把含客户端签名的第三方方块方法纳入服务端初始化路径。

## 最终选择

- 采用统一可选模组反射边界。
- 类探测通过 `Class.forName(className, false, loader)` 完成，不触发静态初始化。
- 成员解析统一通过 `ReflectiveMemberSupport`，沿类层级查找 declared 成员，并吞掉 `LinkageError` / `SecurityException`。
- LootGames 扫雷兼容不再反射调用 `SmartSubordinateBlock#getMasterPos` 或 `BoardBorderBlock#getMasterPos`，改为根据 LootGames 棋盘稳定布局解析 master 坐标、board origin 和炸弹坐标。

## 选择原因

- 只捕获当前异常属于小补丁，后续其他可选模组或其他客户端签名仍可能在初始化阶段拖垮服务端。
- `getDeclaredMethod(...)` 能降低风险，但仍不足以表达项目级边界，也不能覆盖类探测、字段解析和后续新增适配器。
- 统一边界能把“可选兼容失败”稳定降级为“能力不可用”，而不是影响 Qz-Miner 主流程启动。

## 影响范围

- `ClassNameCompatSupport` 成为可选模组类探测入口。
- `ReflectiveMemberSupport` 成为可选模组成员解析入口。
- LootGames 扫雷坐标解析依赖当前 LootGames 棋盘布局：master 位于棋盘西北角外侧，当前棋盘原点为 `master + (1 + offset, 0, 1 + offset)`，`offset = (allocatedBoardSize - currentBoardSize) / 2`。

## 后续注意事项

- 新增可选模组兼容时，不要在服务端初始化路径使用 `Class#getMethod(...)`、`Class#getField(...)` 等会扫描公开成员的反射 API。
- 第三方类同时存在服务端逻辑和客户端方法时，优先寻找稳定数据结构、接口或方块元数据规则，避免直接反射客户端相关方法。
- 升级 LootGames 后需要复核棋盘布局、边框 meta 顺序和 `MSBoard` 字段名是否仍然匹配。
