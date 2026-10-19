# 分车型基线与本地固件候选

宋、唐分别使用维护者提供并确认已在对应车型实测的原始固件。`firmware/baselines.json` 固定两份 ZIP、六个外层成员以及各自 HMI/界面库/资源的 SHA-256。这里的原包实测记录来自维护者；本项目没有将它继承为修改版的实车验证。

| 开发目标 | 原包服务版本 | 原包 ZIP SHA-256 | 修改版状态 |
| --- | --- | --- | --- |
| `song-plus-dmi-2021` | 1.0.0 | `659de3b001937f9b4330c80a792d27c0f8eb4b86d483fe643b9dcdbfd9443033` | 本地基线扩展研究候选 |
| `tang-plus-dmi-2021` | 1.0.3-1017 | `b5d188688a3420f4a92609545616cef362b1d47f148770172cf3b6048606e07b` | 本地基线扩展研究候选 |

两份原包的 HMI 程序、`ui.so` 和 KZB 各有不同摘要；编译出的播放器绑定对应基线。两包共有的系统或 MCU 字节不代表仪表板号相同，唐的开发目标名称也不扩展为所有唐车型。

## 本轮离线实现

1. 无 SDK 编译器为每个播放器生成独立基线表。`--identity` 仅报告编译目标；`--baseline-check` 只读核对 `/base/app/rc_hmi/rc_hmi.exe`、`ui.so`、`bic_byd_stha.kzb` 和 `/proc/boot/libc.so.3` 的完整字节数及 SHA-256。先 lstat 拒绝链接/管道/设备等非普通文件，使用已核对的 O_NONBLOCK 打开，读取前 fstat、读取后 fstat/lstat 比较 dev/ino/size/mode/属主/时间/链接数；变化或失败就关闭本次描述符并拒绝启动。
2. `--baseline-serve` 在任何网络监听和媒体库加载前完成上述检查，端口限定为 **47792**，占用则失败，不终止原服务。开发者原有 `--tcp`/`--serve` 仍是未绑定平台的手动实验入口；基线检查不是安全沙箱或硬件身份认证。
3. 本地构建工具重新检查原包 ZIP CRC、六成员摘要、SV 压缩体 MD5、gzip CRC/长度和 TAR 路径/类型/结束标记。原 TAR 的全部记录原字节保留，只在结束标记前追加 `base/app/openbyddisplay/`；不解包执行脚本，也不覆盖 HMI、原服务和启动入口。
4. `app.sv` 只更新压缩体及 footer 中的压缩体 MD5、展开字节数；其余 footer 字节保持原样。`os.sv`、`mcu.sv` 和三个版本文件逐字节保留。遇到未登记的外层成员（含 `patch.sv`）或 TAR 扩展/链接会拒绝，需要单独审查新基线，不静默丢弃。
5. 加入四个自研原生构建产物、构建记录、车型配置和手动入口。构建记录与已提交源码绑定，冻结已校验的输入字节；不收录用于链接的厂商库。候选报告由独立 Ed25519 开发密钥签名，绑定候选 ZIP 摘要、原包及车型，核验必须显式指定可信公钥摘要。

签名是离线产物来源和完整性检查，不是原仪表刷写器对本项目密钥的信任，也没有完成仪表端原生验签安装。候选包含用户提供的第三方原件，工具强制输出到仓库外；不放入 GitHub、Release、公开接机包或自动刷机目录。公开仓库仅包含独立源码、摘要和合成测试。

## 本地复现

先按[开放工具链](qnx66-open-toolchain.md)使用对应原机 libc 分别构建两车型。新构建报告必须覆盖 `baselines.json`，旧的四产物不能代替本轮新播放器。提交并审查源码，保持工作树干净。安装 `firmware/requirements.txt`，使用仓库外保存的自有 Ed25519 开发私钥，**不要使用第三方凭证或固件里的密钥**。

```sh
# 占位路径应替换为本机对应材料；输出必须位于仓库外且尚不存在。
python3 firmware/tools/model_firmware.py build \
  --target song-plus-dmi-2021 --baseline /local/materials/song-original.zip \
  --native-build /local/builds/song --private-key /local/keys/development-private.pem \
  --output /local/delivery/song-research-candidate

python3 firmware/tools/model_firmware.py verify \
  --target song-plus-dmi-2021 --baseline /local/materials/song-original.zip \
  --directory /local/delivery/song-research-candidate \
  --public-key /local/trusted/development-public.pem \
  --expected-public-key-sha256 TRUSTED_PEM_SHA256
```

唐使用独立的 `--target tang-plus-dmi-2021`、唐原包、唐构建和唐输出目录。构建输出报告可信公钥 PEM 字节摘要，需由操作者保存；不能把候选文件夹中任意附带公钥当作预先建立的信任。

`firmware-candidate.zip` 具有原六文件容器结构，**结构校验通过不等于允许刷入**。它没有接管启动，没有生产原生安装器或独立恢复入口，不是“开机即可投屏”的自研固件。原增强服务保持原样，本项目不改变其凭证或授权。

## 首次连接后的开发入口

先走[接机流程](preflight.md)，从探针、库/显示读取和基线检查开始。读到的平台资料确认后，再确定本车型允许的矩形与层级。不要凭宋的参数为唐创建窗口，也不把原文件指纹匹配当成实际板号、告警优先级或恢复成功的证据。

已有本项目文件、独立人工恢复终端，且已确认允许窗口参数后，开发者可以手动运行：

```sh
# 仅编译身份，无平台访问
./obd-qnx66-player-song-plus-dmi-2021.elf --identity
# 只读平台文件核对；不设置 USB、创建窗口或播放
./obd-qnx66-player-song-plus-dmi-2021.elf --baseline-check
# 后续显式开发会话语法；每个大写变量必须先由实际平台信息确定
./obd-qnx66-player-song-plus-dmi-2021.elf --baseline-serve \
  ADDRESS 47792 SESSION DISPLAY PHYSICAL_W PHYSICAL_H X Y W H Z
```

App 的“自研视频服务端口”手动设为 **47792**，使用自研视频直连流程；不向原固件 47782/47785 发送本项目无商业凭证的协议。此播放器只处理视频及同一视频会话内的布局配置；原 OEM HMI 服务的 start/stop、授权和 USB 切换不是它提供的能力。停止会话和退出保留现有媒体清理机制，清理结果不明时退出 75、停止再次接收。

文件读取是限字节数的同步调用；QNX 文件服务或媒体库阻塞尚无整进程硬期限。lstat→open 不是原子 no-follow，检查也不能防御恶意 root 控制的文件系统；需要避免同时更新基线文件。没有猜测 O_NOFOLLOW 位值，或硬编码要求所有 `/proc/boot` 文件只有一个硬链接。

只读适配的 ARM32 小端 stat 使用原 libc 的 72 字节 ABI：ino@0、size@8、dev@16、uid/gid@24/28、mtime/ctime@32/40、mode@44、nlink@48。官方 6.6 文档的字段顺序与本地原指令交叉核对：固定 libc 摘要 `72c66804…` 中 fstat `0x1cc98` 请求长度 `0x48`，lstat `0x20450`；原模块的 stat 读取也使用相同偏移。没有复制 SDK 头文件或厂商代码。主机普通文件/FIFO/链接/替换测试和 QNX 布局替身测试分别记录；它们不代替 QNX 文件服务调用实测。

## 验收分层

| 项目 | 离线可核验 | 仍需要目标资料或实测 |
| --- | --- | --- |
| 原基线 | 每个原包/成员/本车型资源指纹 | 精确板号、当前实际安装版本 |
| 编译 | 两个 ARM32 ELF、依赖/重定位、源码摘要 | QNX loader/libc 初始化真实执行 |
| 包构建 | 原记录保留、仅自研目录新增、签名、错车型/破坏拒绝 | 原机刷写流程、空间/挂载/失败恢复契约 |
| 视频 | H.264 真样片、C 引擎和 ACK/心跳/重连/清理替身 | 解码插件、实际像素、窗口层级、持续运行 |
| 原生安装 | 已有签名增量主机实验和 C 试启动策略 | QNX 验签/文件系统后端、独立健康检测/恢复入口 |
| HMI/USB | 本候选保留原机内容，记录独立服务边界 | 自研 USB/HMI 后端、告警优先和启动接管 |

只在每个具体平台完成真实启动、投屏、停止、异常与恢复验收后，才发布该平台的可刷固件。这里保留研究候选与生产安装之间的差距，不用完整 ZIP 结构代替验收。

接口依据：[QNX 6.6 open](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.lib_ref/topic/o/open.html)、[stat 字段与类型](https://get.qnx.com/developers/docs/6.6.0.update/com.qnx.doc.neutrino.lib_ref/topic/s/stat_struct.html)、[fstat](https://qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.lib_ref/topic/f/fstat.html)、[SHA-256 标准](https://csrc.nist.gov/pubs/fips/180-4/upd1/final)。
