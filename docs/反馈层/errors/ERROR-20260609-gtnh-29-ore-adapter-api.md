# 错误记录：GTNH 2.9 矿石与线缆 API 迁移

## 错误现象

- 升级到 GTNH `2.9.0-beta-1` 后，`./gradlew.bat compileJava` 首次失败。
- 旧 `MixinBWTileEntityMetaGeneratedOre` 找不到 `bartworks.system.material.BWTileEntityMetaGeneratedOre`。
- 旧 `MixinTileEntityOres` 对 `TileEntityOres#getDrops(...)` 的 `@Redirect` 与 `@ModifyExpressionValue` 找不到目标方法。
- `GregTechCableCompatAdapter` 调用的 `BaseMetaPipeEntity.issueClientUpdate()` 不再存在。

## 触发场景

- 将开发依赖从 GTNH `2.8.4` 升级到 `2.9.0-beta-1`。
- 保留旧版 GT / BW / GT++ 矿石掉落 mixin 注入点和旧 GT 线缆客户端刷新 API。

## 根本原因

- GTNH 2.9 beta 将 GT / BW / GT++ 普通矿掉落逻辑迁移到 `gregtech.common.ores.GTOreAdapter`、`BWOreAdapter`、`GTPPOreAdapter`。
- `gregtech.common.blocks.TileEntityOres` 在 2.9 beta 中只保留矿石数据字段，不再承载 `getDrops(...)`。
- BW 普通矿旧 tile entity 路径不再是当前掉落逻辑入口。
- GT 线缆同步 API 从旧 `issueClientUpdate()` 收敛为 `issueTileUpdate()`。

## 修复方案

- 将 GT / BW / GT++ 普通矿时运 mixin 迁移到 `GTOreAdapter`、`BWOreAdapter`、`GTPPOreAdapter`。
- 在 GT / BW adapter 的 `getOreDrops(...)` 中拦截 `OreInfo.isNatural` 读取，保持 `enableFortuneForPlacedOre` 语义。
- 在三个 adapter 的 `getBigOreDrops(...)` 中继续用 `MixinExtras @ModifyExpressionValue` 拦截 `fortune > 3`，保持 `enableUnlimitedOreFortune` 语义。
- 将 GT 线缆刷新改为 `issueTileUpdate()`，并保留 `issueTextureUpdate()` 和 `GregTechAPI.causeCableUpdate(...)`。后续双基线核对确认 `issueBlockUpdate()` 声明 owner 是 `IRedstoneTileEntity`，只承担红石/邻居通知，不应从 `IGregTechTileEntity` 解析或作为线缆替换必需能力。

## 预防措施

- 后续升级 GTNH 依赖时，优先用上游 sources jar 复核 `gregtech.common.ores.*OreAdapter` 的 `isNatural` 和 `fortune > 3` 注入点。
- 不再按旧 `TileEntityOres#getDrops`、`BWTileEntityMetaGeneratedOre#getDrops` 或 `BlockBaseOre#getDrops` 假设矿石掉落入口。
- 编译通过后仍需实机验证高等级时运采掘 GT / BW / GT++ 普通矿和 GT 线缆替换，编译只能确认注入点静态存在。
- 反射 profile 必须按“调用语义 + 声明 owner”共同裁定必需成员；不能因方法在其它父接口存在就从错误接口解析，更不能把与替换无关的通知能力纳入 all-present 门。
