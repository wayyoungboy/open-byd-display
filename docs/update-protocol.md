# 更新协议与实现边界

传输为 USB NCM 网络上的 TCP JSON 行（默认47785）；现有帧协议保留 `update.info/upload/status/apply/cancel`，本项目自研端不接收或要求商业 Token/client_time。电脑实验服务只绑定127.0.0.1，不能作为实车QNX服务使用。

先读 `update.info`，确认 update_protocol=2、receive_available、对应 apply_available 和组件公钥状态。实验服务只允许components安装，firmware_apply_available固定false。`cast.status`在实验端只报告模拟idle，没有实现投屏接收或渲染。

上传请求带 op、update_id（32位小写hex）、kind（firmware/components）、bytes。先收同ID的ok/ready，再发恰好bytes个原始字节，最后读该ID/kind/stage。不要复用4MiB主题上传上限；整包上限512MiB，组件payload上限256MiB加头清单。此路径不在首行加入sha256；包内清单/SV格式承担相应完整性检查。

重复ID的duplicate回复不得触发再次传正文。上传ready不代表安装成功；上传后的stage ready也只是允许用户提交。apply使用update_id，不带kind；实验服务要求parked=true及confirm=INSTALL_COMPONENTS表达用户安装意图。firmware确认词为FLASH_FIRMWARE。停车字段来自用户操作，不声称读取了车辆真实车速；原样本的这些词法字段不能当作硬件停车门已验证。

apply前本机先持久化“已尝试”标志。丢失回复后只查询，不能自动重发；handed_off仅表示交接启动更新，不是已完成。损坏本机任务记录会阻塞新任务；同host、明确update_not_found且未尝试apply的上传ID，可由用户点击清理解除。已经尝试apply的未知任务不能据not_found直接清除。

## FUP2组件包

| 偏移 | 内容 |
| --- | --- |
| 0 | 8字节magic：FUP2、CR、LF、0x1a、LF |
| 8 | manifest长度，LE u32，1..65536 |
| 12 | reserved LE u32，必须0 |
| 16 | payload长度，LE u64，1..268435456 |
| 24 | Ed25519签名64字节 |
| 88 | 原始manifest UTF8 bytes；之后为payload |

签名消息为28字节 `FISSION-COMPONENT-SCRIPT-V2\n` 加原始manifest。manifest恰含format=2、version、package_id和files。version为三段0..65535十进制，无多位前导0。files最多128项，各含path/bytes/sha256，payload严格按files顺序拼接。文件路径不得越界、重复或发生祖先冲突，必须有一个有效install.sh。

独立实验包增加受签名覆盖的xhh-target.json，绑定目标车型、硬件标识及host-lab/production_flashable=false。两目标互不通用。验证使用用户自己的Ed25519公钥，不能让采用其他信任公钥的固件接受此包。

现成更新器会运行组件install.sh；本实验实现**从不运行它**，只验证并将其余数据写入sandbox generation，再原子发布current.json。文件和目录按顺序同步，启动时复查未完成发布；这些主机测试不证明真实仪表掉电可靠性。

ZIP整包需要六个必要文件app.sv/os.sv/mcu.sv/kzbVersion/RcAppVersion/version_svp，可有patch.sv。SV末尾512字节含品牌标识、类型、压缩体MD5和扩展大小；真实更新后端还需要gzip校验、设备专属目的路径、缓存、启动记录与重启协作。版本文件不代替车型/硬件检查。该生产后端尚未实现。
