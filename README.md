<div align="center">
  <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-dark-mode-only">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-light-mode-only">
      <img alt="QzMinerLOGO" width="256" height="256" style="display:block;margin:auto">
  </picture>
</div>

# 爆破连锁

**专为GTNH设计的智能连锁挖矿模组**
✅ 精准处理同矿词矿物 | 🚀 增强型相邻检测 | ⚙️ 自定义配置范围 | 💥 全自动矿脉爆破

## 核心特性

### 🧩 双主模式架构
#### 连锁模式
- **智能邻块检测**：增强型3D范围检测（可配置）
- **精准匹配**：支持方块meta值和掉落物damage校验（目前为硬编码）
- **子模式系统**：
  - 📦 矩形模式：立方体范围限制
  - 🔒 严格模式：精准拆解管道/机械（建议开启挖掘预览，功能尚不完善，保护好自己的基地）
  - 🌳 伐木模式：Y轴无限制树木砍伐
  - 🎯 连锁组模式：纯白名单控制

#### 爆破模式
- **暴力矿脉清除**：中心扩散式遍历挖掘
- **子模式系统**：
  - ⛏ 矿脉模式：全自动矿脉识别（性能优化版）

## 🔧 特色功能
- **扫雷助手**：实时高亮最近地雷方块（按住连锁键激活）
- **智能农作**：右键成熟作物自动收割（保留作物本体）
- **视觉辅助**：实时渲染挖掘区域预览
- **性能突破**：
  - ⏳ 异步线程管理（杜绝主线程卡死）---（目前已知的卡顿源于事件总线，由于事件总线无法监测，故无法解决此方面的卡顿）
  - 🔄 智能缓存机制（解决内存泄漏）
  - 🚫 时运限制解除（突破时运III上限）

---

## 🚀 未来规划
- **探矿权杖**：
  - 右键地表扫描矿脉
- **隧道工程**：
  - 自适应尺寸隧道挖掘（当前支持3x3）
- **2.0版本代码重构**
  - 将会引入全新的UI操作库，在游戏内可视化调整配置
  - 重构异步设计解决一些已知问题

---

## 📸 效果预览
### 支持预览挖掘区域，会渲染出可能被连锁的方块
![a620d5cfb6d195d167f8fefb24974616](https://github.com/user-attachments/assets/6fef1eed-6593-446b-b775-f2b12e451ad8)
![acae9b07ebefffe9c43290144ba0370b](https://github.com/user-attachments/assets/09f6225e-8bbe-425b-b48a-72af94ff8c74)



## 更新日志

<details>
<summary>📜 版本演进</summary>

### v1.12.0+
- 时运上限解除系统
- 人工矿物时运支持

### v1.7.0-v1.8.1
- 多线程任务重构
- 伐木模式实装
- GUI配置修复

### v1.0.0-v1.6.1
- 核心算法奠基
- GTNH 270b4兼容
- 隧道模式初版

</details>
