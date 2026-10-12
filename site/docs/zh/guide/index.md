# 简介

**Cam Kernel SU** 把 Root 放进 Android 内核。配套应用叫 **SU Kernel**。

## 在内核里

Root 位于一个内核模块（`camsu.ko`）中，从 `init_boot` 或 `boot` 镜像加载。

- 不修改 `/system`。模块在开机时挂载。
- 由内核根据你设置的**应用配置**决定哪些应用获得 Root。
- 没有 Root 的应用看不到模块。

## 有什么

- 玻璃界面，史莱姆在应用里四处溜达。
- [防卡开机](./features#防卡开机)与[模块冲突检测](./features#模块冲突检测)。
- [隐藏 Bootloader](./features#隐藏-bootloader)。
- [Root 隐藏检测](./hiding)：用普通应用的眼睛看手机。
- 自动更新。

守护进程是 `camd`，数据位于 `/data/adb/cam`。按 KernelSU 规范编写的模块仍可使用。

::: warning 同一时间只用一种 Root
请先卸载 KernelSU、Magisk 或 APatch。
:::

## 支持的设备

- **GKI 5.10 或更新的内核**：android12-5.10、android13-5.10、android13-5.15、android14-5.15、android14-6.1、android15-6.6、android16-6.12、android17-6.18。
- `aarch64` 与 `x86_64`。
- 已解锁 Bootloader。

下一步：[安装](./install)。
