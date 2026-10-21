# 免 U 盘的自研仪表服务升级

Android **0.1.5 开发版**已接通 C 原生接收器和安装管理器。通过已有 USB NCM 连接，车机上传本地 `.obg`，仪表验签暂存，用户确认后切换自研服务，健康检查通过才报告成功。宋、唐分别编译、打包和验证；当前只有离线证据，尚无 QNX 或实车安装证据，也没有发布生产固件 Release。

## 使用条件和操作

先在对应基底上部署含接收器、管理器、公钥及私有 store 的基础服务。`boot.sh` 不自动创建 store；操作者显式准备后，接收器与 `supervisor --watch` 分别保持运行。旧基础包没有接收器时，安装新版 App 不能凭空增加此能力。首次部署及 USB/HMI 平台验证见[原生安装与启动](native-install-boot.md)。

1. 仪表网络已就绪后，在 App 填写仪表地址，选择对应车型的 `.obg`。
2. 检查包身份和仪表能力，再上传；App 核对车型、基底、公钥 pin，C 接收器核对完整 Ed25519 签名、文件摘要和 profile。
3. 停止投屏并核对车辆静止状态，手动确认安装。`parked` 是操作者确认，不是实际车速信号。
4. 收到等待确认的回执后，可显式开始向同一仪表投屏，再点击刷新跟进试启动；该阶段只读状态查询可与投屏并行，上传/安装/清理仍互斥。`awaiting_confirmation` 表示尚未确认完成；只有原生管理器确认健康，才是 `applied=true`。此时需要真实新鲜的视频和窗口回读；无视频时不能用“进程还活着”冒充出画。

仅更新私有目录中的自研服务代际，保留已确认的旧代际；不刷 OS、MCU 或分区，不运行包内脚本。完整系统恢复仍依赖各车型已核实的恢复入口。未校准的交付 profile 为 `enabled=false`，允许上传验签、禁止安装试启动；不能只改扩展名或关闭校验绕过该条件。

## 协议 3 / TCP 47793

端点允许 loopback 或已配置的 `192.168.77.1`，不负责切换 USB 角色或配置 IP。若基底使用私有 SOCK，接收器和播放器使用同一个显式命名空间；服务更新不得更换该命名空间。网络尚未出现时接收器等待监听条件恢复。现有视频口 47792、实验更新口 47785 各自独立。

每个连接先收一个最多 1024 字节的 ASCII JSON 行。不接受未知键、重复键、嵌套对象或任意文件路径；下列符号值须替换为实际数据。

```json
{"op":"update.info"}
{"op":"update.upload","update_id":"32位非零小写hex","kind":"native_generation","bytes":123456,"sha256":"整包SHA256","target":"song-plus-dmi-2021","baseline_sha256":"原始基底ZIP的SHA256"}
{"op":"update.status","update_id":"同一任务ID"}
{"op":"update.apply","update_id":"同一任务ID","parked":true,"confirm":"INSTALL_NATIVE_GENERATION"}
{"op":"update.cancel","update_id":"同一任务ID"}
```

先收到同 ID 的 `ok/ready` 才发恰好 `bytes` 字节正文，再收最终回执。包总上限 `16 MiB + 208 + 16×128`，与 ZIP/FUP2 上限分开。重复任务返回 `duplicate=true`，客户端不再发送正文；同 ID 不同摘要或长度会拒绝。

`update.info` 的协议号必须为 3，并报告 `native_generation_available/receive_available/target/baseline_sha256/public_key_sha256/native_max_bytes`。`public_key_sha256` 指 raw32 公钥摘要，首次读取后绑定本次任务；此协议没有 TLS，不把 pin 检查描述为网络对端认证。旧 OS/MCU 整包和 FUP2 安装能力固定 false。

安装资格还要求有效且已启用的 profile、无其它试启动、版本高于已激活高水位（失败试启动也不降低）。宋为 `song-plus-dmi-2021`，唐为 `tang-plus-dmi-2021`，基底与运行指纹取各自 `firmware/baselines.json`。共享 libc 不意味着 HMI 或硬件可互换。

## 中断和失败

接收器用单独生命周期锁和带摘要的持久任务记录，传输期间释放安装事务锁。短暂启动锁竞争有有界重试；损坏记录/时钟失败立即拒绝继续。收包中断须显式清理，未完成正文不会续传。正式激活前持久记录 APPLYING；结果丢失后查询状态，不自动重新提交。

管理器在空闲和已确认代际运行时观察候选，先结束并确证回收自己拥有的旧子进程，再试启动候选；健康失败回到已确认旧代际，无旧版本则回到 OEM 路径。不按历史 PID 杀原车进程。候选回退、激活结果不明、子进程回收不明及任务损坏分别保留恢复状态；不能用一次 TCP 成功清除。

接收器/安装器/管理器属于基础服务，`.obg` 只替换自研播放器与签名 profile，不能自我替换升级守护程序。基础服务更新仍需单独验证的部署流程。

## 复现离线验证

```sh
python3 -m pip install -r firmware/requirements.txt
cmake -S firmware/native -B /tmp/obd-native -DOBD_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/obd-native
ctest --test-dir /tmp/obd-native --output-on-failure
./headunit/gradlew -p headunit :app:testDebugUnitTest --tests org.openbyddisplay.NativeUpdateInteropTest
python3 -m unittest discover -s firmware/tools/model_tests -v
python3 -m unittest discover -s firmware/tools/qnx66_tests -v
```

跨语言测试运行实际 Kotlin 客户端、C TCP 接收器、验签与磁盘安装器；包内为主机子进程夹具，由真实管理器接收媒体 ABI 替身回报并确认，不执行厂商程序。另有 watch/健康失败回退测试、进程中断和回执丢失测试。ARM 链接和指令模型不等于 QNX 系统调用或真实 USB/显示验证。

上车后宋、唐分别验证：运行指纹/板号；原生加载与目录权限；NCM 和 SOCK；上传中断/回执丢失；真实健康确认；失败回退与警告显示优先；正常重启及独立恢复入口。硬件断电测试需单独方案，不能引用主机进程中断结果代替。
