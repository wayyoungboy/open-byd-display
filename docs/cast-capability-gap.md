# 熊猫投屏能力核对与 OpenBYDDisplay 差距（2026-10-19）

样本：智能守卫 `V3.0.26`（`cc.omycar.magicsentry`，versionCode 3026）；仪表固件对照宋版 `Fission-Instrument-D129-1.0.0` 与唐版 `1.0.3-1017`。APK **未内嵌** 可刷仪表 `app.sv` 整包，仅含 `assets/instrument-defaults/factory-1.0.2.json` 与遮罩 PNG。

## 固件能力（鉴权以外）

| 能力 | 端口/证据 | 说明 |
| --- | --- | --- |
| 状态/版本探测 | 47777 `version`/`ping` | 唐 1.0.3：`query_protocol=3`，公开授权方案描述 |
| 视频推流 | 47782 BVL1→LHF2/H.264 | 1920×720@~30；CRC/序号/关键帧 |
| 接收控制 | 47783 `cast.arm/set/stop/keepalive` | 窗口几何、图层 |
| HMI 投屏事务 | 47785 `cast.start/switch/stop/status/heartbeat`、`cast.profile.*`、`mask.*`、`theme.*`、`page.*`、`update.*` | epoch/revision；页面锁/布局；更新协议 2 |
| 导航/歌词叠加 | 47786/47787 `navigation.*` / `music.*` | overlay-service；0～5 行歌词等 |
| USB Host/NCM | `usb-service` + `usb.json` | USBMODE1/2；唐版默认 `target_mode:2` |
| 模块启停/超时 | `module-control`、`timeout-run`、`service-tools` | fission-services 启动链 |
| 主题/遮罩资源 | `rc_hmi/custom/mask`、Kanzi KZB | 唐新增 fullscreen3 / mask3 |

公开许可证（Ed25519 / token+client_time+op）属于鉴权层，自研路径不实现签发或绕过。

## App（智能守卫 3.0.26）投屏相关功能

- 仪表服务开关、投屏总开关、自动投屏
- 开始/停止/重启投屏；方案 1/2/3（全屏/左半/中半，以仪表实际 profile 为准）
- 录屏/虚拟屏名称、投屏目标主题、自定义遮罩与上下边框遮罩
- 仪表歌词（跟随音乐助手）、简易地图（跟随高德文字/图标）
- 锁定信息显示、页面开始/结束策略、壁纸及主题编辑
- USB 投屏模式切换、连接状态、固件/主题相关工具入口
- 与 1.0.3 配套：签名许可证、serverTime 校时、授权续期分支、ASCII JSON 规范、HMI heartbeat

## 差距表（相对 OpenBYDDisplay 补齐前 → 本轮）

| 项 | 熊猫 | 补齐前 | 本轮 | 实现方式 |
| --- | --- | --- | --- | --- |
| BVL1/LHF2 发送 | 有 | 仅 host 接收/工具 | App `VideoStreamClient` + `Lhf2Framing` | App |
| MediaProjection 投屏服务 | 有 | 无 | `CastForegroundService` | App |
| cast.start/stop/status/heartbeat/profile | 有 | 仅 status 桩 | 协议 + lab 实现 + App 探测/会话 | App+lab；真机需自研 QNX 服务 |
| 按面积选全宽 profile | 3.0.26 要求 | 无 | `CastProfile.select` | App |
| 柔边遮罩 | 厂商 PNG | 无 | procedural `SoftEdgeMask` | App（未拷贝厂商图） |
| 歌词/导航叠加协议 | 有 | 无 | `MusicOverlayProtocol`/`NavOverlayProtocol` + lab stub + 偏好开关 | App+lab；真机渲染需 QNX overlay |
| USB Host/NCM 切换 | 有 | 无 | **未做**（需车机特权/厂商接口） | 待实车方案 |
| 页面锁/主题编辑器/壁纸 | 有 | 无 | **未做** | 多需 HMI/原生 |
| 商业许可证握手 | 有 | 明确拒绝 | 继续拒绝 token/client_time | 设计如此 |
| QNX live-receiver/hmi/overlay | 有 | 研究核心+探针 | 未刷机验证 | **需 SDP 6.x ARMv7** |

## 因缺 SDP 6.x 无法上车验证

自研 `obd-qnx66-*` 播放器/媒体库、投屏出画、USB 角色切换回读、Kanzi 叠层、刷写与断电恢复。本轮 **不** 标记 `production_flashable`。
