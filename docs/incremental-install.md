# 签名增量安装与试启动实验

`firmware/openbyddisplay/incremental.py`实现FUP2签名增量、完整代际重建、试启动确认和未确认回退。**它是电脑上的文件系统事务实验，不是QNX安装器，不操作车载启动入口或分区。** 安装脚本不执行；原有App更新服务会拒绝此类包，避免把差量内容误当完整组件安装。

每个包仍由自有Ed25519签名，绑定车型和硬件标识。新增受签名覆盖的`obd-incremental.json`，记录前一代package ID、版本和完整树摘要，以及下一代完整文件清单、SHA-256、字节数、模式。载荷只包含变更文件；未变文件从经过复查的基线复制，删除文件不进入新代际。只有0600数据和0700程序两种模式，拒绝链接和路径冲突；不会覆盖旧代际。

流程为：`build → stage → activate → boot → confirm`。stage验证签名、基线、载荷后同步新代际，尚不切换。activate再次核对基线和版本水位，原子保存候选与确认nonce。boot在返回候选路径前持久化“已尝试”；下次boot仍未确认就回到上一确认版本。首次试用失败则返回空路径，表示原系统后备。confirm仅接受本次已尝试的候选ID和nonce，再复查文件。回退不会降低最高版本水位；缺失/损坏已有state时失败关闭，不重置版本。

boot只返回路径，**不会执行服务或重启系统**。调用confirm表达的是实验操作者确认；目前没有读取实际仪表出画或健康传感器。QNX启动器将来必须先调用对应的原生事务实现，再独立验证健康，不能直接读取current字段启动。

## 电脑实验命令

先用[实验服务说明](instrument-lab.md)生成本地开发密钥，准备只含自研文件的`lab-state/source/`。在仓库根目录设置：

```sh
export PYTHONPATH=firmware
# 所有命令使用相同的root/public-key/target配置。root必须为新建或已有专用0700实验目录。
python3 -m openbyddisplay.incremental --root lab-state/incremental-song \
  --public-key lab-state/keys/development-public.pem --target song-plus-dmi-2021 \
  build --source lab-state/source --key lab-state/keys/development-private.pem \
  --version 0.1.0 --output lab-state/song-incremental-0.1.0.fup
```

后续以相同前缀依次运行`stage PACKAGE`、`activate PACKAGE_ID`、`boot`、`confirm PACKAGE_ID NONCE`。ID来自stage结果，nonce来自activate结果。已确认首版后修改source并用更高版本build，会自动绑定已确认基线。`status`只读当前状态。不要公开开发私钥或把实验包刷进现有仪表。

测试覆盖未变文件复制、移除文件、程序模式、跨车型/错误签名/错误基线、状态丢失/损坏、文件破坏/链接、确认身份、重复启动回退和多个发布阶段的模拟进程中断。这些验证针对macOS/Linux实验文件系统；不能证明QNX掉电耐久性。生产端仍需原生验签/事务端移植、设备身份、扩展启动入口、安装目的地、文件系统同步保证、独立恢复入口及实测。
