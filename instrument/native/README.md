# 仪表原生接收核心研发

这是自研仪表端服务的第一部分：独立实现LHF2帧头、CRC32、Annex B NAL类型、关键帧依赖及序号/时间回绕校验。没有账号、Token、VIP或商业许可证；不包含厂商程序或反编译代码。

`core/` 为无平台依赖C99库。`obd-lhf2-unpack` 从文件提取已校验的H.264；`obd-bvl1-receive` 在电脑回环地址接受一次BVL1/LHF2会话，将已校验的码流存为新文件。两个工具都不是解码器或QNX固件。校验不自动推进会话状态，后端接收后由调用者显式commit。收到帧或提交ACK均不能作为实际出画证据。

```sh
cmake -S instrument/native -B /tmp/openbyd-video-build -DOBD_SANITIZE=ON
cmake --build /tmp/openbyd-video-build
ctest --test-dir /tmp/openbyd-video-build --output-on-failure
python3 instrument/native/tests/check_real_video.py /tmp/openbyd-video-build/obd-lhf2-unpack
python3 -O instrument/native/tests/check_tcp.py /tmp/openbyd-video-build/obd-bvl1-receive
```

两个Python检查需要FFmpeg/ffprobe和libx264编码器：生成10帧真实H.264、封装LHF2，经文件和真实TCP接收后分别完整解码。TCP测试另验证分片、无效握手/元数据、坏CRC、截断、重复帧、会话帧数上限、超时和已有文件保护。NAL检查不代替完整H.264语法验证。

文件工具接受显式EOS或完整帧边界的EOF，并在结果的`termination`字段区分两者；帧头、负载或EOS被截断都会拒绝。文件通过校验不代表解码或实际出画。

## 回环会话契约

运行 `obd-bvl1-receive 0 NEW_OUTPUT.h264`，首行报告绑定的127.0.0.1端口。只接受一个连接，5秒等待/单次I/O期限、30秒会话期限，最多60帧和64MiB。不能指定外部网卡。输出以0600、排他创建，已存在文件不会覆盖；正常错误清除本次未完成输出，进程被强制终止可能留下部分文件，只有退出0才可使用结果。

握手为ASCII `BVL1`、BE u32正文长度、**精确的UTF-8 `{"op":"stream"}`**，再跟四个BE u32：编码宽、高、FPS、reserved=0。宽/高必须为正偶数，分别不超过1920/1080；FPS为1..60。它是独立实现的严格子集，不是任意JSON解析器，不接收Token或商业许可证。声明的尺寸尚未与SPS核对。

ready回复为JSON行，标明`backend=host-file`、`decoded=false`、`visual_verified=false`。LHF2为24字节帧头：ASCII magic，随后五个BE u32为负载字节数、序号、PTS毫秒、关键帧0/1、IEEE CRC32；每帧最多2MiB。帧内容是Annex B H.264 AU，首帧及丢帧后的恢复帧需要IDR+SPS+PPS。序号和PTS允许正向小范围u32回绕。

每次写入并flush文件后回`accepted_sequence`，只表示当前host sink接收，后续会话失败仍会删除不完整文件。EOS为长度0、序号0xffffffff、其余0；也接受完整帧边界EOF。EOF路径需发送方半关闭写端以继续读取最终回复。EOS终止会话，不再处理后续字节。至少接收一帧才能成功。

仍需Android编码发送端，以及QNX mm-renderer/mdevpush、Screen窗口、USB角色和恢复适配。旧RTSP/TLS/RGB565/DIC1原型不迁入本路线。现有App只包含更新客户端，本目录尚未接入App的视频传输。平台参考及剩余边界见[仪表固件实现](../../docs/instrument-native.md)。

熊猫材料仅用于确定互操作格式和平台契约。两个车型分别核实硬件、QNX ABI、解码库、显示器/窗口组和刷写恢复入口；静态样本中的1920×720、display ID=2不是通用的实车配置。没有合法QNX工具链与目标验证前，不标记为原生可运行，也不生成面向实车的刷机包。
