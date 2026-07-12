# 决策：矿石时运上限修复策略

## 背景

- `enableUnlimitedOreFortune` 需要对 GT / BW / GT++ 普通矿解除原版 `fortune > 3` 的截断。
- 上游 `getDrops` 会先执行 `if (fortune > 3) fortune = 3;`，之后才调用 `Random.nextInt(...)`。

## 候选方案

- 继续拦截 `Random.nextInt(...)`，重算随机上界。
- 直接拦截 `fortune > 3` 表达式，阻止上游截断。

## 最终选择

- 使用 `MixinExtras` 的 `@ModifyExpressionValue` 拦截 `fortune > 3` 表达式。
- natural 语义由无代际静态链接的 helper 统一处理：2.8 读取 `mNatural`，2.9 读取 `isNatural`，放置矿配置覆盖语义一致。
- 同一 jar 同时携带 legacy 与 adapter mixin，由 plugin 按目标类字节码的方法名和 descriptor 选择；2.9 中保留 `TileEntityOres` 数据类不会误启 legacy 注入。

## 选择原因

- 问题根因在于上游先截断局部变量，再进入随机掉落逻辑。
- 仅修改 `nextInt` 无法保证真实时运等级流入后续公式，容易出现“配置存在但效果不完整”的假修复。
- 直接拦截布尔表达式更贴近语义，后续排查也更容易定位。

## 影响范围

- `club.heiqi.qz_miner.mixins` 下三类 legacy mixin、三类 adapter mixin及其 plugin。
- `FortuneCompatHelper` 与 `mixins.qz_miner.json`。

## 后续注意事项

- 相关 mixin 依赖 `MixinExtras` 表达式注入能力，配置文件需保留 `mixinextras.minVersion`。
- 若未来升级 GT / BW / GT++ 上游版本，需要优先核对 `getDrops` 中 `fortune > 3` 的字节码结构是否变化。
- 每次升级必须同步核对 plugin descriptor，并运行双基线脚本；编译成功不能替代两代真实掉落验证。
