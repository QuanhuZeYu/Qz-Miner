# 错误记录：GT 线缆连锁替换 NPE（双向绑定半截）

## 错误现象

- 用户启动 GT 线缆连锁替换（ChainMode.SPECIAL），服务端 tick 循环抛 `NullPointerException`，集成服务端崩溃。
- 崩溃堆栈：
  ```
  java.lang.NullPointerException: Cannot invoke "...IGregTechTileEntity.getIGregTechTileEntityAtSide(ForgeDirection)" because the return value of "...MetaPipeEntity.getBaseMetaTileEntity()" is null
      at gregtech.api.metatileentity.MetaPipeEntity.disconnect(MetaPipeEntity.java:767)
      at club.heiqi.qz_miner.compat.adapter.gregtech.GregTechCableCompatAdapter.replaceCableWithoutConnections(GregTechCableCompatAdapter.java:112)
      at club.heiqi.qz_miner.chain.executor.GregTechCableReplaceActionExecutor.execute(GregTechCableReplaceActionExecutor.java:79)
      at club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge.consumeContext(ChainExecutionEventBridge.java:288)
  ```
- 实机日志：`run/client/crash-reports/crash-2026-07-06_19.10.08-server.txt`

## 触发场景

- 任何 GT 线缆连锁替换操作：玩家手持异种线缆，对已有线缆启动 SPECIAL 模式连锁。
- 首次替换即崩，非偶发，100% 复现。

## 根本原因

1. **GT API 契约：`setMetaTileEntity` 是单向，`setBaseMetaTileEntity` 才建双向**（字节码已核对 `GT5-Unofficial-5.09.52.594-dev.jar`）：
   - `BaseMetaPipeEntity.setMetaTileEntity(IMetaTileEntity)` 只做 `mMetaTileEntity = (MetaPipeEntity) aMetaTileEntity;` —— **单向 base→meta**
   - `MetaPipeEntity.setBaseMetaTileEntity(IGregTechTileEntity)` 内部调 `base.setMetaTileEntity(this)` —— **一次调用同时设 meta→base 与 base→meta，无邻居遍历、无网络副作用**
   - 旧实现只调了 `base.setMetaTileEntity(newCable)`，`newCable.mBaseMetaTileEntity` 恒为 null
2. **`MetaPipeEntity.disconnect(side)` 第一行解引用 `getBaseMetaTileEntity()`**：返回 null 即 NPE。`disconnect`/`connect` 是邻居状态机，假设 `getBaseMetaTileEntity()` 非空、邻居 TE 已就位、世界已加载。
3. **更深一层：即使不崩，TE 也恒死**：`CommonBaseMetaTileEntity.hasValidMetaTileEntity()` 要求 `mMetaTileEntity.getBaseMetaTileEntity() == this`，双向绑定半截时恒 false → `getMetaTileEntity()` 返回 null → 整个 TE 在 GT 眼里失效。
4. **连接状态真相：只是个 `public byte mConnections` 位掩码**：`MetaPipeEntity.mConnections` 与 `BaseMetaPipeEntity.mConnections` 均为 public byte；base 每 tick 从 meta 同步；能量网络图（Node/NodePath）通过 `GregTechAPI.causeCableUpdate` 从位掩码重建，**不依赖 connect/disconnect 回调**。故两阶段 REPLACE→RECONNECT 架构完全多余。

## 修复方案

修复落点 commit `d325b71`（分支 `fix/cable-atomic-replace-binding`）。

1. **绕开 connect/disconnect 高级封装，单阶段原子替换**（`GregTechCableCompatAdapter.java:102-165`）：
   - 复用同一 `BaseMetaPipeEntity`，只换挂载的 meta 实例与 mID
   - `newCable.setBaseMetaTileEntity(base)` 建立双向绑定（一次调用，无邻居副作用）
   - `base.setMetaTileID(newMetaId)` 换 mID
   - 直写 `newCable.mConnections = oldConnections` + `base.mConnections = oldConnections`（两个 public byte 字段）恢复连接
   - `refreshPipe`（issueTextureUpdate + issueBlockUpdate + issueTileUpdate + causeCableUpdate）刷新 + 重建网络图
   - mutate 段 try/catch `RuntimeException` 精确回滚（oldCable 重绑 + 恢复 mID + 恢复位掩码 + refreshPipe + WARN 日志）
   - 成功后才 `consumeReplacementStack`
2. **RECONNECT 两阶段停用**（`GregTechCableReplaceActionExecutor.java`）：
   - `canExecute` 简化（只校验目标仍是线缆）
   - `execute` RECONNECT 分支直接 `return false`（防御历史残留）
   - `enqueueFollowUpTargets` 恒 `return false`（不再入队 follow-up reconnect 目标）
   - SessionState 中 RECONNECT 相关字段留 Batch 2 清理

## 预防措施

1. **GT TE meta 替换必须用 `setBaseMetaTileEntity` 建双向，不能只 `setMetaTileEntity`**：这是 GT API 的隐式契约，`setMetaTileEntity` 单向、`setBaseMetaTileEntity` 双向。任何动 GT meta 实例替换的代码（不限于线缆）都必须遵守，否则 TE 恒死且 `disconnect`/`connect` 必 NPE。
2. **GT 线缆/管道的连接状态是位掩码，不是状态机**：`mConnections` 是 public byte，直接位操作 + `causeCableUpdate` 即可重建网络，不需要也不应该走 `connect`/`disconnect`。这两个方法会解引用 `getBaseMetaTileEntity()` 且遍历邻居，在批量替换/生命周期边界场景下脆弱。
3. **客户端视觉同步由 GT 标准 description packet 路径覆盖**（字节码已核对）：`getDescriptionPacket` → `getInitialDataForClient` 第一步 `putShort(mID)`；客户端 `receiveMetaTileEntityData` 检测 mID 变化后 `createNewMetatileEntity(newMID)` 重建 + `issueTextureUpdate` 刷新纹理。`issueTileUpdate()` → 下 tick `world.markBlockForUpdate` 触发发包。换 mID 后客户端一定能看到线缆类型变化，无需额外操作。
4. **GT 源码不在项目源码树，契约复核靠 javap 字节码**：`D:\Apps\.Env\Gradle\.gradle\caches\modules-2\files-2.1\com.github.GTNewHorizons\GT5-Unofficial\5.09.52.594\` 下的 dev jar 可用 `javap -c -p -cp <jar> <类名>` 反查方法实现。GTNH 升级时此契约可能变化，需重新核对 `setBaseMetaTileEntity` 是否仍内部调 `base.setMetaTileEntity(this)`，以及 `mConnections` 是否仍 public。
5. **两阶段架构在底层位掩码可直接操作时是过度设计**：原 REPLACE→RECONNECT 两阶段是为了绕开"替换时丢了连接方向"的问题，但既然 `mConnections` 是 public byte 可直接搬，单阶段即可完整恢复连接，两阶段反而引入了 connect() 危险 API 的二次调用。后续设计兼容层时，优先确认底层是否有直接数据通路，再决定是否需要多阶段。
