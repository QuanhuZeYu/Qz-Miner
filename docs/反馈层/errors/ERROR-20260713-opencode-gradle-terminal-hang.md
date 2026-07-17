# OpenCode Gradle 终端挂起

## 错误现象

agent 直接运行 Gradle 时可能长期占用终端，无法可靠区分活动、终态发布窗口与孤儿，并诱发强杀或重复启动。

## 触发场景

Windows 子 agent 直接调用 wrapper、临时拼装后台命令或同步等待长构建。

## 根本原因

缺少双重运行身份、带 mutation guard 的原子单例锁、启动前 metadata、PID 启动时间核验、身份绑定终态和有界等待合同；仅靠旧 exit 文件或 RunId 所有权不足以排除 ABA 竞态。

## 修复方案

建立 `qz-gradle-opencode/v1`：严格参数 allowlist；Start 自生成 RunId/invocationId，先发布 `PREPARED` 再启动；锁的创建、读取、回收和按完整 token 释放均在固定 guard 内。Poll 仅接受身份一致的 metadata/锁/sentinel，有效 pending 可收口，损坏或不可观察状态保锁并返回 `INCOMPLETE`。

## 预防措施

按角色限制协议使用，禁止子 agent 直接 wrapper、自造 Start-Process、kill 或 `--stop`；运行态与双基线不授权。
