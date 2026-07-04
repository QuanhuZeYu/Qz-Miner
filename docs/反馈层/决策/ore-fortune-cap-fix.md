# 决策：矿石时运上限修复策略

## 背景

- `enableUnlimitedOreFortune` 需要对 GT / BW / GT++ 普通矿解除原版 `fortune > 3` 的截断。
- 上游 `getDrops` 会先执行 `if (fortune > 3) fortune = 3;`，之后才调用 `Random.nextInt(...)`。

## 候选方案

- 继续拦截 `Random.nextInt(...)`，重算随机上界。
- 直接拦截 `fortune > 3` 表达式，阻止上游截断。

## 最终选择

- 使用 `MixinExtras` 的 `@ModifyExpressionValue` 拦截 `fortune > 3` 表达式。
- 保留 `mNatural` 相关 `@Redirect`，仅替换原先用于重写 `nextInt` 的 mixin。

## 选择原因

- 问题根因在于上游先截断局部变量，再进入随机掉落逻辑。
- 仅修改 `nextInt` 无法保证真实时运等级流入后续公式，容易出现“配置存在但效果不完整”的假修复。
- 直接拦截布尔表达式更贴近语义，后续排查也更容易定位。

## 影响范围

- `src/main/java/club/heiqi/qz_miner/mixins/MixinTileEntityOres.java`
- `src/main/java/club/heiqi/qz_miner/mixins/MixinBWTileEntityMetaGeneratedOre.java`
- `src/main/java/club/heiqi/qz_miner/mixins/MixinBlockBaseOre.java`
- `src/main/resources/mixins.qz_miner.json`

## 后续注意事项

- 相关 mixin 依赖 `MixinExtras` 表达式注入能力，配置文件需保留 `mixinextras.minVersion`。
- 若未来升级 GT / BW / GT++ 上游版本，需要优先核对 `getDrops` 中 `fortune > 3` 的字节码结构是否变化。
