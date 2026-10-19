# 原生安装、启动与恢复

当前已实现 C 验签安装器和自研服务启动管理器，能够为宋、唐分别构建 ARM32 ELF 与接入启动入口的本地完整台架镜像。QNX loader、驱动和实车显示未验证，镜像不作为面向用户的可刷发行版。原始基底的实车记录为维护者提供，不能转写成修改版已验证。

| 能力 | 宋/唐源码 | 离线验证 | 正式发布/实车 |
| --- | --- | --- | --- |
| 自有 Ed25519、文件摘要、车型/基底绑定 | 已实现，分别编译 | 原生文件事务、编译后 ARM 验签指令 | 均未验证发布 |
| 排他暂存、代际发布、试启动、高水位、失败回退 | 已实现 | 真实主机文件系统与进程中断 | QNX 文件系统待验证 |
| 异步入口、子进程租约、健康确认 | 已实现 | 主机 fork/exec/pipes 与异常父退出 | QNX 待验证 |
| 自有 H.264 解码/窗口后端 | 已交叉链接 | Screen/mm-renderer 替身、真实 AVC 数据 | 实际窗口与画面待验证 |
| USB 顺序、软连接屏障、恢复策略、固定 argv | 原生事务库 | 故障替身测试 | 真实进程身份/控制器适配尚未接入 |
| HMI 会话围栏、归属回执、确认/恢复/手动让出 | 原生事务库 | 故障替身测试 | OEM theme/center/mode 适配尚未接入 |
| 刷写、断电恢复、行车显示验收 | 未验证 | 无硬件证据 | 两车型分别验收 |

`libopenbyddisplay-platform.so.0` 包含事务策略和 USB 参数构造器，尚不是完整 USB 角色服务。视频服务只修改自己的 Screen 窗口，不改变 OEM 整窗 alpha。未接入的 OEM 联动不报告可用。

## 原生包与文件事务

`.obg` 是独立格式 **OBDGEN1**，不是 FUP2/ZIP，不可送到现有 App 的 FUP2 上传入口。上限 16 个平面普通文件、总载荷 16 MiB；必须有 `service.elf` 和 64 字节 `profile.dat`，没有安装脚本。208 字节头包含车型、原包 SHA256、包 ID、版本、表摘要和签名，每个文件表项 128 字节。Ed25519 签名域 `OBD-NATIVE-GENERATION-V1\n` 覆盖规范头和完整表，所有载荷在落盘前核对 SHA256。

原生验签使用 Monocypher 4.0.3 标准 Ed25519，来源、摘要和许可见 `native/vendor/monocypher/`。安装器绑定公钥的 **raw32 SHA256**，镜像报告使用 **PEM SHA256**，二者不可混填。没有账号/VIP/商业授权门；签名、摘要、车型和基底检查保留。

安装器只管理显式私有服务目录，拒绝链接父目录和系统保留路径，不访问分区、启动记录或 MCU。`init` 只能建立新目录；已有或损坏目录不自动重置。`stage` 先验完整包，再写并同步 `pending-ID`，最后发布不可变 `gen-ID`；中断暂存隔离保存，不从半包续跑。`state.next` 文件同步、rename、父目录同步全部成功后才算提交，不明确的结果阻止交接。

`activate` 绑定新包和一次性 nonce。管理器在 exec 前持久记录 attempted；未确认代际在下一次允许的选择中回退到已验证旧 current，无旧 current 则退出自研路径。版本高水位不下降。QNX 安装器不提供裸 `confirm`/`select` 命令，健康确认由管理器发起。

## 启动与异常恢复

原 SLM、`start-hmi.sh`、HAM 和原扩展入口保留。新增入口只异步启动自己的 `boot.sh`，不自动初始化目录、装包或切换 USB。台架操作者须显式执行 `prepare-store.sh`，其中只运行本项目原生安装器；现有 store 不会被该脚本覆盖。

管理器只 exec 验签后的固定 `gen-ID/service.elf`，使用固定参数/环境，不继承 SOCK 和加载器搜索变量，只终止自己 fork 的子进程。专用管道提供父租约；父死亡或租约中断会使服务退出并清理自己的媒体资源，fd5 保持生命周期锁引用。试启动确认要求同一存活子进程、持续新鲜的帧/Screen POST/窗口属性回读，表示平台资源健康，**不表示人眼已看到地图**。

fork 前先持久写入 `supervisor.uncertain`，确证回收子进程后才清除并同步。异常死亡、回收不明或同步失败保留门，后续启动拒绝接管。异常重启后须先检查自研进程、窗口和 USB，再人工解除恢复门；不自动删除门或按历史 PID 杀进程。主机进程中断不等于实车断电实验。

QNX 非阻塞等待使用原 6.6 调用核对的 `WNOHANG=0x40`，有非 host 分支回归。参考 [QNX 6.6 flock](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.lib_ref/topic/f/flock.html)、[waitpid](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.lib_ref/topic/w/waitpid.html)；文档和静态 ABI 核对不代替目标执行。

## 分车型构建与核验

按[开放工具链](qnx66-open-toolchain.md)分别编译，增加 `--platform`，得到安装器、管理器和事务库。源码先提交，镜像打包前后核对工作树与 HEAD、原生报告源码和产物摘要。构建期运行库视图不能复制到目标或公开分发。

显式 JSON 配置字段：`enabled/display/physical_width/physical_height/x/y/width/height/zorder/trial_seconds/address`。display 和矩形须各车型校准；材料中的 display 2、1920×720 仅作台架参考。zorder 为 0～9，地址仅允许 loopback 或 `192.168.77.1`，端口 47792。未校准交付配置为 `enabled=false`。

可选 `network_runtime_id` 为非零的 32 位小写 hex，只能生成 `/fs/tmpfs/OpenBYDDisplay/<本车型>/<ID>/net` 的显式 SOCK 环境，不能注入任意路径。它让播放器能够接入本项目私有网络栈；配置存在不代表控制器已切换、车机已枚举或对端实际连接。

```sh
python3 firmware/tools/bootstrap_firmware.py build \
  --target song-plus-dmi-2021 --baseline /absolute/path/song-original.zip \
  --native-build /absolute/path/song-platform-build \
  --private-key /absolute/private/owner-key.pem \
  --config /absolute/path/song-bench-profile.json \
  --output /absolute/private/song-boot-bench

python3 firmware/tools/bootstrap_firmware.py verify \
  --target song-plus-dmi-2021 --baseline /absolute/path/song-original.zip \
  --directory /absolute/private/song-boot-bench \
  --public-key /absolute/private/song-boot-bench/image-public.pem \
  --public-pem-sha256 TRUSTED_PUBLIC_PEM_SHA256
```

唐改用 `tang-plus-dmi-2021` 和自己的原包/构建/配置/输出，不使用宋的 HMI 指纹。工具产出完整六文件 ZIP、独立 `.obg` 与签名报告，只重写 app.sv 自研目录和 os.sv 的一个精确指纹启动脚本，其他 TAR 记录、MCU、版本文件保持原样，不触发升级。原升级器版本/目标/恢复条件仍待台架验证；原包仍是系统恢复基底，原生代际回退不代表可恢复整个原车系统。

完整镜像含维护者提供的原固件，只留本机；GitHub 仅提交源码、测试与文档。
