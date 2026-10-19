# OpenBYDDisplay 首次接机包

用于首次只读采集、原生探针和分阶段短测试。**不是仪表刷机包，没有可刷固件，不修改永久自启。**

先读 `docs/preflight.md`，执行 `python3 tools/preflight.py verify-kit .`（Windows 用 `py -3`）。APK 在 `headunit/`；宋、唐独立测试程序在 `firmware-test/`。不要交叉选择目标或从文件名推定硬件匹配。App 需匹配自研接收器，安装后不会自动让现成熊猫或其他固件出画。

先采车机只读报告，确认仪表 QNX 入口、硬件和运行库。probe 成功后才用 inspect；显示/恢复条件确认后，再使用 64×48、5fps、10帧样片和播放器。每步保留 stdout、stderr、退出码和目视结果；失败停止该阶段，不跳到刷写。

采集结果放包外新目录。manifest 记录精确源码、文件摘要及已核验 APK 的包名/版本/证书；校验不会联网或执行 ELF。车机/QNX/实车状态仍待测试，不因主机测试通过自动置为已验证。

包内文档为接机摘录，工具路径优先以 `docs/preflight.md` 为准。完整文档相对链接及仓库构建命令在 `OpenBYDDisplay-source.zip` 中，解压后阅读。

定制请联系作者：https://github.com/wayyoungboy 。定制类仪表固件需要收费，验收要求不变。测试版本与风险说明见源码 README。
