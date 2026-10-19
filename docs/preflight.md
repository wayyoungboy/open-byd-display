# 首次接机与离线准备

本阶段的完成条件是：离线代码与失败路径检查通过，宋/唐独立测试程序、开发 APK、测试样片、只读采集工具、精确源码和操作顺序一起交付。**这表示可以开始首次接机排查，不表示功能全部迁移、QNX 已运行或固件可刷。** 总目标仍以[迁移验收清单](feature-migration.md)为准。

| 接机必要项 | 离线实现 | 接机后的证据 |
| --- | --- | --- |
| 车机 App | 0.1.4 共存包；Kotlin/Lint/构建和代码审查 | 真实 Binder、音源、副屏、GPU、灯控/近光反馈与恢复 |
| Android 基线 | 固定只读命令、输出/时间上限、部分失败独立记录 | 实际权限、显示、网络和 APK 报告 |
| 最小探针 | ARM32 CRC/LHF2 自检，开放工具链/CPU 模型 | 原机加载器/libc 下的输出、stderr、退出码 |
| 平台诊断 | 独立 inspect，主机故障注入 | 库/符号、显示 ID/尺寸、mmr 连接和清理 |
| 视频试验 | 持续接收器、分阶段错误、10 帧样片和 EOS | 真实解码/像素、原车优先、停止/拔线恢复 |
| 试启动核心 | 原生 C 提交/CAS、版本水位、一次试用、核验与健康回调 | QNX 持久记录、验签、健康/恢复适配；不是已完成安装器 |
| 接机包 | APK 包名/版本/证书、原生源码摘要、整包白名单校验 | 目标硬件身份仍未知，不能从标签推定 |
| 笔记本使用 | Windows/macOS/Linux 校验/采集；Windows 工具纳入 CI | 实际 ADB 授权、仪表执行入口、恢复通道 |

当前只用 H.264，不增加 H.265。未实现的 OEM HMI、独立导航/歌词、外观资源、原生安装/自启/USB 角色仍保留在迁移清单，不能统称为“只差实测”。平台写入部分先取得下文基线证据，不猜分区、SLM、显示层级或硬件身份。

## 笔记本校验与采集

接机包工具仅用 Python 标准库。需 Python 3.10+、Android platform-tools 和数据线；报告放包外的新目录，不自动上传。

macOS/Linux，在解压后的接机包根目录运行：

```sh
python3 tools/preflight.py verify-kit .
mkdir -p ../preflight-local
python3 tools/preflight.py collect --adb /absolute/path/to/adb --serial DEVICE_SERIAL \
  --target song-plus-dmi-2021 --output ../preflight-local/android-song.json
```

Windows PowerShell：

```powershell
py -3 tools/preflight.py verify-kit .
New-Item -ItemType Directory -Force ../preflight-local
py -3 tools/preflight.py collect --adb 'C:\Android\platform-tools\adb.exe' --serial DEVICE_SERIAL --target song-plus-dmi-2021 --output ../preflight-local/android-song.json
```

替换 `DEVICE_SERIAL`；多设备必须明确指定。唐使用 `tang-plus-dmi-2021` 和另一个新文件。设备名不作为 Android 证据，先读取真实 SDK。采集不安装 APK、不配置 adbd、不切 USB、不授权 app-op、不连接仪表 TCP、不启动投屏/更新。单条命令最多 20 秒/128 KiB，缺命令/拒绝/超时保持 unavailable。屏蔽所选 ADB 序列号及明确敏感字段行，**不保证完整匿名化，分享前人工检查**。

APK 在 `headunit/`，包名必须为 `org.openbyddisplay.usbtest`。先核对证书与旧包兼容，再由操作者安装；不要为签名冲突卸载旧包或清数据。默认不启用灯效、近光联动、启动恢复或 USB 切换；原生律动继续禁用。

## 仪表阶段顺序

1. 停车，记录准确车型、仪表板号/硬件、当前版本、系统及独立恢复方式，宋/唐分别记录。车机 ADB 不等于仪表 QNX 入口；没有入口时停在只读采集，不尝试刷包取得入口。
2. 在已授权仪表 shell 手动运行 `sh collect-qnx-readonly.sh`，stdout/stderr 留在笔记本。只读内核、UID、文件系统、网络及已知运行库摘要，缺失保持未知；路径摘要不等于实际加载映射。不要复制/替换原机库。
3. 先核对运行库摘要与对应 `build-report.json`，确认允许的临时目录及执行权限，再只上传对应 probe。仅为本次自研测试 ELF 设置执行位（`chmod 700 ./对应文件名.elf`），手动运行并保留退出码。它仅做加载/协议自检；失败保存错误并停止，不能用 CPU 模型通过代替。
4. probe 成功后才运行 `obd-qnx66-inspect-<target>.elf --platform`。它临时创建 Screen context 和 mmr 连接，不建窗口或播放。stage 1=API、2=context、3=显示列表、4=显示属性、5=mmr；library_index 0=Screen、1=mmrndclient、2=strm、3=当前进程。缺符号报告名称；清理不明停止。库内部阻塞没有进程级硬期限，保留能从外部终止进程的终端。
5. 显示范围/层级和原车告警优先确认后，按[媒体说明](native-media.md)手动运行播放器。先使用包内 `samples/test-64x48-5fps-10frames.bvl1` 做 stdin 短测试，再做明确私有地址的 TCP 短测试。样片编码参数为 **64×48、5fps、10帧**，物理显示参数使用实测值。`send_video.py` 确认本项目 service/wire_version 后才送视频，不对其他协议接收器发样片。
6. 短测试和清理确认后再用 App 持续投屏，验证停止、拔线、撤权及异常恢复。ready、ACK、Screen POST、属性回读和目视正确画面分别记录。有基线后再研发原生安装/启动/回退和 HMI 接入。

接机包中的 TCP 发送命令如下，替换实际仪表私有地址和本次自研播放器已配置的空闲测试端口；Windows 将 `python3` 换成 `py -3`。不要覆盖或终止既有仪表服务来腾出端口。

```sh
python3 tools/send_video.py INSTRUMENT_IPV4 samples/test-64x48-5fps-10frames.h264 \
  --port TEST_PORT --width 64 --height 48 --fps 5
```

包内文档是接机摘录；其中 `firmware/tools/...` 等命令属于源码树。完整相对链接与重建说明请解压 `OpenBYDDisplay-source.zip`，在其 `OpenBYDDisplay-source/` 目录阅读。首次接机使用本页明确给出的包内 `tools/`、`samples/`、`firmware-test/` 路径。

## 停止与撤回

灯效点击停止并恢复，近光点击停止交回原生，等待确认并关闭已开启的启动恢复选项。USB 不明时只刷新原请求，不重复切换；按 App 人工恢复入口等待并核对。投屏正常发送 EOS 并等清理；异常时从保留终端向**明确属于本次测试的 PID**发 SIGTERM，确认进程/所属窗口退出。无法确认清理或原车恢复时停止并留日志。

只删除本次上传到已确认临时目录的自研测试文件。这些步骤不写永久自启/分区，不靠重刷卸载测试程序；不要杀 OEM HMI、批量 kill 或删原车文件。加载/运行异常不循环重试，不覆盖运行库消除错误。

## 维护者重建

macOS/Linux 使用 Clang/ELF LLD、Python 依赖及**私下保留的匹配原机 libc**，按[无 SDK 构建](qnx66-open-toolchain.md)分别生成新目录。先过原生 Debug/Release CTest、ARM/CPU、采集/打包测试与 Android CI。Windows 原生可校验/采集；C/QNX 重建及依赖 `fcntl` 的增量实验使用 Linux/WSL2，不复用 Mac venv/二进制。

评审并提交后打包，APK/证书摘要取实际构建记录，输出目录必须不存在：

```sh
python3 firmware/tools/make_preflight_kit.py \
  --song-build /private/build-song --tang-build /private/build-tang \
  --apk /private/OpenBYDDisplay-development.apk --apk-sha256 APK_SHA256 \
  --build-tools /Android/sdk/build-tools/34.0.0 --certificate-sha256 CERT_SHA256 \
  --output /private/OpenBYDDisplay-preflight
```

打包器冻结核验后的字节，工具/文档来自绑定 commit，拒绝脏工作树、源码漂移、错误目标/摘要/证书/正式包名。**不复制 `link-inputs-not-for-deployment/`、libc/加载器、厂商固件、私钥或实验 FUP。** 包内白名单与摘要复查不是作者签名、硬件兼容或可刷资格。正式分发继续走 GitHub，不发布占位刷机资产。
