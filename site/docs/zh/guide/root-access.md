# 授予 Root

## 在应用里

打开 **Superuser** 页，选择应用并开启 Root。每个应用都有自己的**应用配置**：

- **Root：**应用调用 `su` 时获得的 UID、用户组、capability 与 SELinux 上下文。默认值适合大多数应用。
- **非 Root：**是否为该应用**卸载模块挂载**。开启后，银行应用和游戏看不到模块文件。

配置页还有**信息**（路径，点按复制）、**存储**和**管理**卡片：备份 APK、清除缓存或数据、冻结、卸载（包括系统应用）。

## 通过 adb 或 Termux

在已有 Root 的 shell 中：

```sh
camd allow add com.termux        # 授予 Root
camd allow remove com.termux     # 撤销 Root
camd allow list                  # 拥有 Root 的应用
```

## 修补时

安装时的 **Apps to grant root** 可以让应用在首次开机时就获得 Root（见[安装](./install#首次安装-修补启动镜像)）。

## SU Kernel 自身的 Root

内核始终为 `cam.su.kernel` 授予 Root，**前提是**它使用项目官方密钥签名。内核读取的是 Android 安装时已验证的签名（`/data/system/packages.xml`），从不打开 APK。

- 卸载后重新安装应用不会失去 Root。
- 同包名的仿冒应用拿不到 Root；如果它接管了一个原本有 Root 的 UID，Root 会被收回。
- 自行编译或用其他密钥签名的应用**没有** Root。
