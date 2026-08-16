# ERROR-20260816-clean-build-rfg-up-to-date-corruption.md

## 现象

- Miner `clean build` 后 `:compileJava` 报 100 个错误：`无法访问 GuiScreen / 找不到 net.minecraft.client.gui.GuiScreen 的类文件`。
- 单独跑 `patchedMcClasses` 显示 UP-TO-DATE，但 `build\classes\java\patchedMc` 只剩 179 个类文件（cpw/ibxm/paulscode 等库包），MC 本体类全缺。

## 原因

- 同一会话内先 `clean`（期间被文件锁打断过一次）、又 `gradlew --stop` 杀守护进程，导致 Gradle 的 up-to-date 输出指纹与磁盘真实状态脱节：clean 已删除 patchedMc 输出，但任务快照仍判定 UP-TO-DATE，后续增量构建不再重编译 MC 类。
- RFG 链路的 MC 编译产物位于 `build\classes\java\patchedMc`（不是 `build\patchedMc`），容易被误判为「产物在别处」。

## 修复

- `gradlew build --rerun-tasks`（强制重跑全链）后恢复正常，后续增量构建正常。
- 若 `--rerun-tasks` 仍不行，删除工程内 `.gradle` 目录重算指纹。

## 备注

- 避免在 clean 中途杀守护进程；clean 失败（文件被占用）时优先解决占用后重跑，而不是叠加 `--stop` + clean。
