# 常见问题

## 能和 KernelSU、Magisk 或 APatch 一起用吗？

不能。同一时间只用一种 Root 方案。SU Kernel 应用也不能与官方 KernelSU 内核配合。请先移除旧的 Root（刷回原厂镜像）。

## 我的手机能用吗？

需要 GKI 5.10 或更新的内核，以及已解锁的 Bootloader。运行 `adb shell uname -r`：看到 `android12` 到 `android17` 且内核为 5.10 或更新即可。

## 刷入后应用显示“未安装”

- 检查刷入的分区：Android 13 及以后出厂的手机通常是 `init_boot`，更早的是 `boot`。
- 镜像必须是当前 ROM **完全一致**的原厂镜像。
- 应用必须是[下载](/zh/download)页的官方版本。自行编译或其他密钥签名的版本没有 Root。

## 其他应用无法调用 `su`

刷入后的首次开机，请先打开一次 SU Kernel，让它安装 `/data/adb/camd`。然后在 Superuser 页授予 Root，或使用 `camd allow add <包名>`。

## 模块已安装，但文件没有出现在 /system

你没有元模块。从[模块库](/zh/modules)安装**一个**（Magic Mount-rs 或 Hybrid Mount）并重启。

## 银行应用仍然检测到 Root

1. 在该应用的配置中开启卸载挂载（并且不要给它 Root）。
2. 打开 [Root 隐藏检测](./hiding)，查看泄露了什么、由哪个模块引起。
3. 如果没有任何泄露但应用仍然拒绝，它很可能使用了 **key attestation**：安全芯片签名的证书表明 Bootloader 已解锁，任何软件都无法改变。

## 每次更新应用都要重刷吗？

不用。应用会自动更新。只有主页出现**重新安装 LKM** 按钮（内核部分确实改变）时才需要重装，并且可以直接在手机上用**直接安装**完成。

## 源码？

在[源码](/zh/source)页提交申请。
