# 功能页

**功能**页（位于模块与设置之间）汇集了 SU Kernel 的独有功能。

## 防卡开机

camd 会统计未能到达 `boot-completed` 的启动次数。连续第 3 次失败时，它会停用**刚安装、刚更新或刚重新启用**的模块；如果没有这类模块，则停用所有已启用的模块。

- 主页显示红色卡片和对话框，可逐个**重新启用**或**忽略**；模块卡片带有“因启动失败自动停用”标签。
- 触发时会发送系统通知。
- 卡片中可设置失败次数（1 到 4）、先停用可疑模块还是全部停用，并查看被停用的模块。

::: warning 限制
无法处理发生在 `post-fs-data` **之前**的卡开机（内核、`init`，或过早挂载的元模块）。此时请用音量键进入安全模式。
:::

## 模块冲突检测

camd 会找出被**多个模块同时修改**的文件（包括 whiteout）、`.replace` 目录以及 `system.prop` 属性。

- 模块卡片显示**冲突**标签；安装时会给出警告。
- 开关、安装时警告、属性检测、列表和重新扫描都在卡片中。命令行：`camd module conflicts`。

冲突不等于错误；它解释了一个模块为什么可能弄坏另一个。最终谁生效取决于元模块的挂载顺序。

## 隐藏 Bootloader

在 `post-fs-data` 与 `boot-completed` 时，camd 会把暴露解锁状态的属性（`ro.boot.verifiedbootstate`、`ro.boot.vbmeta.device_state` 等）改回已上锁的值。如果 Bootloader 没有填写 `ro.boot.vbmeta.digest`，应用会用从你自己的分区计算出的真实值补上。

- **检查 attestation** 会在 AndroidKeyStore 中创建密钥并读取 RootOfTrust，让你看到读取证书的应用会看到什么。

::: info 无法改变 key attestation
使用 **key attestation**（由安全芯片签名的证书）的银行应用仍会看到 Bootloader 已解锁。修改属性无法改变它。
:::

## 无线 ADB

用 Root 开启 adbd 的 5555 端口（类似 `adb tcpip`），无需配对。卡片会显示可复制的 `adb connect <Wi-Fi IP>:5555`。

- 可在 15、30、60、120 分钟后或永不自动关闭（默认 30）。重启也会关闭。
- 开启期间银行应用可以发现它（Root 隐藏检测页的 `adb` 项）。

## Root 隐藏检测

从此页打开：见 [Root 隐藏检测](./hiding)。
