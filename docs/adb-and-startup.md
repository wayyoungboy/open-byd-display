# 开机自启与ADB助手

从0.1.1起，App提供“开机自动打开App”开关，默认关闭。开启后接收系统BOOT_COMPLETED，尝试打开应用窗口；不恢复上传、不提交安装、不自动启动投屏。强行停止应用后，Android通常不会向其发送开机广播，需要先手动打开一次。

Android10/API29及以上限制后台打开页面。本项目在系统允许悬浮窗权限时尝试打开；缺少权限时，如允许通知则提供点击打开的通知。没有通知权限时需手动打开。具体车机仍可能有厂商自启管理，必须通过真实重启验证。App不绘制悬浮窗；权限用于满足后台启动条件。官方规则见 [Android文档](https://developer.android.com/guide/components/activities/background-starts)。

在电脑安装Android platform-tools，通过USB连接车机，并在车机上允许USB调试。无需QNX SDK。

```sh
# 只读：选择设备、检查版本、应用、后台打开权限和IPv4网卡
python3 headunit/tools/adb_setup.py --serial 车机序列号

# 显式配置：允许本App的SYSTEM_ALERT_WINDOW app-op，并打开App
python3 headunit/tools/adb_setup.py --serial 车机序列号 --configure-autostart
```

然后在App中打开自启开关，重启车机验证。脚本不自动打开该开关。电脑上有多台设备时必须指定序列号；开发共存包加 `--package org.openbyddisplay.usbtest`，ADB不在PATH时加 `--adb /实际路径/adb`。Windows可用 `python` 代替 `python3`。

脚本只设置上述App的单个app-op；不使用root，不授予WRITE_SECURE_SETTINGS或厂商签名权限，不修改仪表USB模式或网络地址，不安装或卸载应用。当前USB/NCM输出用于诊断，不能据此认定仪表已连接。后续需要特定ADB能力时分别实现和验证，不批量授予未使用权限。

撤销自启：关闭App中的开关；如不再需要后台打开权限，可在系统权限设置中撤销。升级请使用相同发布签名的APK覆盖安装，不要为解决签名冲突卸载或清空原应用数据。
