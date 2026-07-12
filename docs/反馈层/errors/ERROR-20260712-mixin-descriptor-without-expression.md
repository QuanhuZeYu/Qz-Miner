# Mixin 方法存在但表达式已迁移

## 错误现象

GTNH 2.9.0-beta-1 客户端在 Mixin 准备阶段崩溃：`MixinBlockBaseOreLegacy` 的 required 表达式注入 0 命中。

## 触发场景

GT++ 的 `BlockBaseOre#getDrops(World, int, int, int, int, int)` descriptor 仍存在，但实现已成为委托 adapter 的壳，不再包含 `fortune > 3` 表达式。仅按类名与 descriptor 判断的插件错误启用了 2.8 legacy mixin。

## 根本原因

方法签名是调用契约，不是方法体能力契约。上游迁移实现时可以保留 descriptor，同时移走 Mixin 所依赖的字段读取或比较表达式。

## 修复方案

- 能力门用 ASM 同时核验精确方法 descriptor、所需 `GETFIELD` 与对应 fortune 参数的 `ILOAD/ICONST_3/条件跳转/ICONST_3/ISTORE` clamp 形状。
- GTNH 2.9 GT/BW 的 `OreInfo.isNatural` 改用 `@ModifyExpressionValue`，handler descriptor 固定为 `(Z)Z`。
- required 注入保持 `require = 1`，能力漂移由启动失败暴露，不用 `require = 0` 掩盖。

## 预防措施

每次上游代际升级都用真实目标字节码或等价 ASM 夹具覆盖“完整表达式为 true、委托壳为 false、同 descriptor 无表达式为 false”，并跑双基线完整验证与客户端启动回归。
