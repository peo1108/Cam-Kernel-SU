# 模块与元模块

模块是一个 ZIP，可以添加或替换系统文件、在开机时运行脚本，或自带网页界面（WebUI）。在**模块**页安装：点按安装并选择 ZIP，或从**模块库**获取。

## 元模块

Cam Kernel SU **不会自己**把模块挂载到 `/system`。这项工作由**元模块**完成：`module.prop` 中带有 `metamodule=1` 的特殊模块。

- 只安装**一个**元模块。camd 只保持一个处于启用状态，并链接到 `/data/adb/metamodule`。
- 没有元模块时，模块脚本仍会运行，但模块文件不会出现在 `/system` 中。
- [模块库](/zh/modules)中有两个选择：**Magic Mount-rs** 或 **Hybrid Mount**。二选一。

## 模块库

[模块库](/zh/modules)是精选列表：负责挂载的元模块、Zygisk（Zygisk Next 或 ReZygisk，二选一）、Play Integrity Fix、bindhosts 等。与应用内的模块库相同。

## 给模块作者

- 数据路径是 `/data/adb/cam`。写死 `/data/adb/ksu/...` 或 `/data/adb/ksud` 的模块会出错。
- 同时提供 `KSU*` 与 `CAM_*` 环境变量；WebUI 同时提供 `ksu` 与 `cam` 桥接。
- `sepolicy.rule` 中写的 `ksu` 域只作用于 `ksu`，不作用于 `cam`。

## 模块出问题时

- **卡开机：**见[防卡开机](./features#防卡开机)。
- **一个模块弄坏另一个：**见[模块冲突检测](./features#模块冲突检测)。
- **模块更新后出错：**旧版本会被保留；点按 **Previous** 标签（或 `camd module restore <id>`）即可恢复。
- **模块泄露 Root：**安装或更新模块后的首次开机，应用会自动扫描一次，告诉你哪个模块泄露了什么（[Root 隐藏检测](./hiding)）。
