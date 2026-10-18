# 不依赖官方SDK的QNX 6.6构建实验

路线是保留原机系统、驱动和运行库，只增加自研服务，再制作匹配硬件的增量包。当前已在macOS上使用Clang和LLD，针对本地参考固件中的真实`libc.so.3`完成ARM32编译与动态链接；没有使用QNX SDK、厂商CRT或修改原始运行库。

**目前生成原生探针、单会话播放器与媒体适配动态库，还不是可刷固件。尚未通过QNX加载器、真实libc初始化或实车测试。**

## 构建方式

需要Python 3.12、可生成ARM代码的Clang、LLVM ELF链接器`ld.lld`。macOS上的Apple Clang可以编译本实验；若已安装Rust，其工具链可能包含可用的`ld.lld`，可用`--lld`指定完整路径。Linux可安装发行版的`clang`和`lld`。不需要下载QNX SDK。

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r firmware/tools/qnx66-requirements.txt
python firmware/tools/qnx66_build.py \
  --libc /absolute/private/path/to/normal-boot/libc.so.3 \
  --expected-libc-sha256 YOUR_VERIFIED_LOWERCASE_SHA256 \
  --target song-plus-dmi-2021 \
  --output lab-state/qnx66-song-build
```

输入必须是自行取得并核对来源的原机运行库。明确区分正常启动镜像与恢复镜像中的库；名称相同不代表内容相同。SHA-256为必填项，构建前后均检查。输出目录必须不存在。唐目标使用`tang-plus-dmi-2021`和另一个新目录；目标标签不会验证硬件，也不表示宋、唐可以互刷。

编译使用`--target=armv7-none-eabi`产生ARMv7-A、base PCS、ARM指令的freestanding对象，然后直接调用ELF版LLD。它不借用主机系统头文件或主机链接器。输出检查包括ELF架构、解释器、依赖、未定义符号、重定位类型、入口映射和可写/可执行段。

## 解决运行库缺少节表的问题

参考运行库保留了`PT_DYNAMIC`、动态符号及GNU hash，但去掉了链接器需要的部分节表。`firmware/tools/qnx66_elf.py`按有界动态表、hash和文件映射读取真实符号，为本地副本追加供链接器读取的节表与符号视图。代码和函数地址来自原始输入，不生成假函数，不修改原文件，不复制厂商启动代码。

`link-inputs-not-for-deployment/`仅为本地构建中间目录。其中的`libc.so.3`视图**不得部署、替换原机库或随项目分发**。仓库及CI不含第三方运行库；构建不授予其再分发权。

独立入口仅适用于这个最小C探针：转交初始`argc/argv/envp/auxv`和加载器回调，调用原机`_init_libc`后运行`main`。目前未实现应用构造器、C++、TLS对象和线程的完整启动支持。不能直接把它当成通用QNX CRT。

## 输出及验证边界

| 输出 | 用途 | 当前证据 |
| --- | --- | --- |
| `obd-qnx66-probe-<target>.elf` | CRC32、LHF2头自检并输出JSON；无文件写入、网络、USB或显示操作 | 真实运行库链接成功；编译后的ARM指令通过CPU模型测试 |
| `libopenbyddisplay-video.so.0` | 自研LHF2核心与媒体适配，仅直接依赖原机`libc.so.3` | 链接及ELF静态检查通过；尚未在QNX加载 |
| `obd-qnx66-player-<target>.elf` | stdin/TCP收帧、原生解码/窗口适配、有限会话与清理 | 编译/链接通过；QNX运行待验证 |
| `build-report.json` | 工具版本、输入摘要、产物摘要、导入与重定位、验证状态 | 保留`qnx_execution_verified`、`hardware_verified`、`firmware_flashable`及`vehicle_visual_verified=false` |

探针仅向标准输出写结果并退出；它不检测实际车型。成功输出也不能证明动态库、解码器、窗口或刷写功能可用。

```sh
python -m unittest discover -s firmware/tools/qnx66_tests -v
```

CI从自主C源码构造一个合成运行库，检验缺节表、错误架构、损坏动态表、缺少导出、摘要不匹配及已有输出保护。Unicorn执行实际编译的ARM启动和校验指令，检查参数、回调、栈对齐、短写与写失败。**CPU模型替换了加载器及所有外部运行库调用，不执行QNX或厂商libc。** CI不会把合成库构建物作为实车程序发布。

## 形成可刷增量包还需什么

1. 在匹配的仪表上核实硬件、正常启动运行库和加载器，并以临时目录中的探针确认实际加载与退出。先不修改自启或原机文件。
2. 验证已实现的QNX网络、mm-renderer/mdevpush和Screen后端及停止恢复，见[媒体适配](native-media.md)。
3. 核实原机扩展启动入口、安装目的地、版本切换和独立恢复方式；实现保留原车启动流程的增量安装器。
4. 分别完成宋、唐硬件的测试，再签名、预检并发布对应更新包。

具体显示与安装契约见[仪表原生实现](instrument-native.md)。源码编译和链接问题已有可复现方案；可刷状态取决于后续目标平台验证，不能靠改扩展名或装入ZIP获得。
