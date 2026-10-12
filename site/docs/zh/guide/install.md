# 安装

::: danger 先读这里
刷错启动镜像可能导致无法开机。务必保留当前 ROM 的**原厂镜像**以便刷回，并先备份数据。
:::

## 准备

- **已解锁**的 Bootloader。
- 电脑上安装 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)（`adb`、`fastboot`）。
- 与**当前运行的 ROM 完全一致**的原厂 **init_boot** 镜像（Android 13 及以后出厂的手机）或 **boot** 镜像（更早的手机）。
- 从[下载](/zh/download)页获取最新的 SU Kernel。

### 查看 KMI

```sh
adb shell uname -r
```

输出类似 `5.15.148-android13-8-...` 即 KMI 为 `android13-5.15`。一般不需要记：应用修补时会自动选择正确的内核模块。

## 首次安装：修补启动镜像

1. 安装并打开 SU Kernel。
2. 在主页点按 **Tap to install**，再选择 **Select a file and patch**。
3. 选择复制到手机里的 `init_boot.img`（或 `boot.img`）。
4. 弹出 **Apps to grant root**。SU Kernel 始终包含在内；可添加其他首次开机就需要 Root 的应用。
5. 可选：**Block kernel modules** 阻止某些内核模块在开机时加载（例如厂商的反 Root 模块）。应用会预填可疑的已加载模块，可自行修改或清空。
6. 点按 **Next**。应用会把 `camsu_patched_<日期>_<时间>.img` 保存到 Download 文件夹。
7. 复制到电脑，让手机进入 fastboot 后刷入：

```sh
adb reboot bootloader
fastboot flash init_boot camsu_patched_xxx.img   # 旧机型：fastboot flash boot ...
fastboot reboot
```

8. 开机后**打开一次 SU Kernel**。首次打开会安装 `/data/adb/camd`，在此之前其他应用无法调用 `su`。

::: tip 在电脑上修补
下载页提供 Windows、Linux、macOS 版 `camd`：

```sh
camd boot-patch -b init_boot.img --kmi android14-6.1
```

修补后的镜像位于当前目录。SU Kernel 依然会获得 Root，因为内核始终为使用官方密钥签名的 `cam.su.kernel` 授予 Root。
:::

## 已有 Root：直接安装

手机已 Root 时（更新 LKM，或从其他 Root 方案迁移），选择 **Direct install (Recommended)**：应用会修补当前镜像并直接刷入，无需电脑。

- 刷入前应用会**停用所有已启用的模块**，让新版本首次开机保持干净。停用失败时会中止，不会刷入。
- 使用**自签 AVB 密钥**（avbroot，`yellow` 状态）的手机会隐藏两个直接安装选项：手机上修补的镜像没有签名，已上锁的 Bootloader 会拒绝启动。

## OTA 更新之后

OTA 会把新系统写入非活动槽位，且不带 Root。OTA 安装完成后（**重启前**），打开 SU Kernel 选择 **Install to inactive slot (After OTA)**，然后再重启。

## 更新应用与内核模块

- 应用每 12 小时检查一次新版本，发送通知，校验签名后用 Root 安装。
- 主页的**重新安装 LKM** 按钮只在内核部分确实改变时出现。没有按钮就不需要重刷。

## 卸载或救砖

- **模块导致卡开机：**连续 3 次启动失败后 camd 会停用最新的模块（[功能页](./features#防卡开机)）。如果在 `post-fs-data` 之前就失败，请使用安全模式（开机时按住音量键）。
- **回退：**每次刷入都会把上一个镜像保存在 `/data/adb/cam/previous/<分区>.img`。首次安装请使用你的原厂镜像：

```sh
fastboot flash init_boot init_boot.img
```

- **完全卸载：**刷回原厂镜像并卸载 SU Kernel。
