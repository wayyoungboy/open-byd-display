# 自研仪表解码显示适配

`firmware/native/qnx66/`已实现mm-renderer、mdevpush和Screen的独立C适配，以及支持短会话和持续接收的BVL1/LHF2播放器。它通过Clang/LLD链接原始ARM32 libc，不使用官方SDK或复制厂商程序。**真实QNX、解码插件和仪表窗口仍待接机测试。**

## 已实现的路径

`BVL1/LHF2 → CRC/序号/NAL校验 → 有超时的mdevpush IPC → mm-renderer → 本会话Screen窗口`

播放器可从stdin读取，或显式绑定本机私有IPv4。`--tcp`接受一次连接；`--serve`保留监听socket串行接受会话。不会设置USB角色、修改接口地址、写OEM PPS、修改其他应用窗口或注册自启。短测试监听等待30秒，输入每次I/O最多5秒并受30秒输入期限约束，最多60帧。持续模式空闲监听可继续等待，每次输入/输出事务有限期，支持空闲心跳、配置与显式结束；单帧仍限2MiB。Screen/mm-renderer库调用的阻塞与停止行为仍需QNX验证；这些不是整个进程的硬期限。

握手只接受`BVL1`＋BE正文长度15＋精确`{"op":"stream"}`＋四个BE u32编码宽、高、FPS、reserved=0。ready在格式描述提交后发出；每帧IPC成功后返回ack和accepted_sequence，并推进序号。EOF不等同EOS，本播放器要求显式LHF2 EOS。只有所属子窗口出现、属性回读成功且观察到Screen POST后，EOS才可能成功；完成消息在资源清理成功之后发送。始终保留`visual_verified=false`。

库加载器逐项检查Screen、mmrndclient、strm和libc的接口符号。输入/输出/上下文字典由mm-renderer调用接管，包括失败返回；strm_dict_set只在成功时接管旧字典。初始化任一步失败、IPC失败、窗口关闭、属性漂移或停止都经过统一清理。只隐藏和配置本会话窗口；查询所得的外部窗口本地句柄会释放，不修改远端窗口。不会持续强制OEM窗口层级。

父窗口使用独立透明RGBA缓冲区，编码尺寸、物理显示尺寸和视频矩形分别校验。显示ID和几何由操作者显式给定，不把参考样本的ID=2和1920×720当成全部车型事实。子窗口需同时匹配会话ID、窗口组和embedded类型，重复所属子窗口会失败关闭。Screen POST、属性回读和实际像素正确性分别记录。

## 产物与后续仪表测试

构建器新增`obd-qnx66-player-<target>.elf`，动态库也包含媒体适配接口。原始libc的memcpy/memset为IFUNC；本程序使用自主实现且隐藏的字节拷贝/清零及必要AEABI适配，避免额外假设加载器对该导入的处理。运行库本身未修改。

**以下为接仪表核实硬件、libc摘要及最小探针运行成功后使用的手动开发命令，不是刷机步骤。** 所有数字先用实测配置替换；单次测试后进程退出。

```sh
# stdin模式：SESSION DISPLAY PHYSICAL_W PHYSICAL_H X Y W H Z
./obd-qnx66-player-song-plus-dmi-2021.elf TEST 2 1920 720 0 0 1920 720 3 < input.bvl1 > session.jsonl

# 单会话TCP：额外指定--tcp、本机私有IPv4和端口；不会建立NCM接口
./obd-qnx66-player-song-plus-dmi-2021.elf --tcp 192.168.77.1 47782 TEST 2 1920 720 0 0 1920 720 3

# 电脑端发送最多60帧、带AUD分隔的Annex-B H.264；尺寸/FPS必须与文件一致
python3 firmware/tools/send_video.py 192.168.77.1 input.h264 --width 1280 --height 480 --fps 15
```

持续接收将上述`--tcp`替换成`--serve`；此模式专用于配合自研App进行开发验证，不自动注册系统服务。操作者必须先核对允许窗口范围；App配置只能在这个范围内收缩/移动。详细控制帧见[投屏协议](casting.md)。

参数中的Z=3仅演示语法，不代表OEM允许的正确层级。Qt/Kanzi/HMI协作、窗口合成、警告保留、USB角色、真实库内部线程/阻塞、异常信号及断线恢复均需目标验证。库符号缺失、插件拒流或属性不能设置时失败退出；不要通过替换原机库来消除错误。

## 离线证据

主机执行同一C媒体引擎，通过显式ABI替身逐个注入初始化失败，验证字典与资源释放、有效的零输出ID/通道ID、所属/外部窗口区别、CRC/序号提交和关闭。真实10帧H.264经该引擎提交并还原后，FFmpeg解码逐帧摘要一致；QNX API仍是替身。ctypes模型库未启用ASan，因为它加载到普通Python进程；独立C校验器、接收器和网络布局测试使用ASan/UBSan。CI不上传厂商库。

接口参考：[QNX 6.6 Screen context](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.screen/topic/group__screen__contexts_1Screen_Context_Types.html)、[Screen events](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.screen/topic/group__screen__events_1Screen_Event_Types.html)、[Screen usage](https://www.qnx.com/developers/docs/6.6.0.update/com.qnx.doc.screen/topic/screen_8h_1Screen_Usage_Flag_Types.html)、[字典所有权](https://get.qnx.com/developers/docs/7.0.0/com.qnx.doc.libstrm/topic/strm_dict_set.html)、[mmr输入参数](https://www.qnx.com/developers/docs/7.0.0/com.qnx.doc.mm_renderer/topic/mmr_input_parameters.html)、[MsgSendvs](https://www.qnx.com/developers/docs/6.5.0SP1.update/com.qnx.doc.neutrino_lib_ref/m/msgsendvs.html)。旧版公开API与本地参考运行库一起用于确定接口；文档版本相近不等于目标ABI已执行验证。
