# 错误记录：LootGames 服务端反射触发客户端签名加载

## 错误现象

- GTNH 2.8.0 dedicated server 安装 Qz-Miner 5.0.16 后，服务端在初始化阶段崩溃。
- 日志显示 `qz_miner` 进入 `UCHE` 状态，核心异常为 `java.lang.NoClassDefFoundError: net/minecraft/client/renderer/texture/IIconRegister`。

## 触发场景

- 服务端加载 LootGames 与 Qz-Miner。
- Qz-Miner 初始化 `ChainModeBootstrap` 时触发 `CompatAdapters` 静态初始化。
- `LootGamesMinesweeperCompatAdapter` 构造阶段反射解析 LootGames 方块类方法。

## 根本原因

- 旧实现使用 `Class#getMethod(...)` 解析 `SmartSubordinateBlock` / `BoardBorderBlock` 的公开方法。
- `Class#getMethod(...)` 会扫描公开方法并解析方法签名，`BoardBorderBlock` 存在 `registerBlockIcons(IIconRegister)` 这类客户端专属签名。
- Dedicated server 不存在 `net.minecraft.client.renderer.texture.IIconRegister`，因此兼容能力探测阶段直接抛出 `NoClassDefFoundError`。

## 修复方案

- 新增 `ReflectiveMemberSupport`，统一使用沿类层级的 declared 成员解析，成员解析吞掉 `LinkageError` / `SecurityException` 并降级。
- `ClassNameCompatSupport` 改为 `Class.forName(className, false, loader)`，避免可选模组探测触发静态初始化，并吞掉链接错误。
- LootGames 扫雷兼容不再反射调用问题方块类的 `getMasterPos`，改为依据 LootGames 稳定棋盘布局解析 master 坐标、board origin 与炸弹坐标。

## 预防措施

- 可选模组兼容层不得使用会扫描整类公开成员的反射 API 作为初始化路径。
- 可选模组类探测和成员解析必须把 `LinkageError` 视为兼容能力不可用，而不是让错误传播到 mod 初始化。
- 涉及客户端/服务端共包但签名含客户端类的第三方类时，优先按稳定数据结构或接口边界解析，不把第三方客户端方法纳入服务端启动路径。
