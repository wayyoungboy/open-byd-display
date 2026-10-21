# 仪表固件研发

本目录包含仪表端原生源码、无SDK构建工具、目标配置、签名组件打包和安装恢复实验。路线为原机系统＋自研服务＋增量包。**原生程序已能编译和链接，QNX实际运行和实车刷写尚未验证。**

宋、唐分别绑定维护者确认已实测的原包。已新增 C TCP 升级接收器（47793）、原生验签安装、试启动/回退、受监管启动管理器，以及保留原 SLM/HMI 的异步启动镜像工具。USB/HMI 事务库已做离线测试，真实驱动和 OEM 联动适配仍未接入；原包实测不继承为修改版验证。App 免 U 盘服务更新见[原生升级流程](../docs/native-update.md)。详见[原生安装与启动](../docs/native-install-boot.md)，早期仅追加目录的候选见[分车型基线](../docs/model-firmware.md)。

| 目录 | 内容 |
| --- | --- |
| `native/core/` | C99 LHF2/CRC/NAL校验 |
| `native/qnx66/` | 独立入口、运行探针、mm-renderer/Screen/mdevpush适配、持续/短会话TCP播放器 |
| `native/host/`、`native/tests/` | 电脑接收器、生命周期替身及真实H.264实验 |
| `tools/` | Clang/LLD构建、ELF检查、ARM指令模型 |
| `openbyddisplay/`、`tests/` | FUP2验签、更新服务及增量试启动/回退实验 |

从仓库根目录运行：

```sh
cmake -S firmware/native -B /tmp/openbyd-native -DOBD_SANITIZE=ON
cmake --build /tmp/openbyd-native
ctest --test-dir /tmp/openbyd-native --output-on-failure
PYTHONPATH=firmware python3 -m unittest discover -s firmware/tests -v
```

原生播放器与平台边界见[解码显示](../docs/native-media.md)，签名增量包与试启动流程见[增量安装](../docs/incremental-install.md)，构建步骤见[无SDK工具链](../docs/qnx66-open-toolchain.md)。Python安装器仅写显式实验目录，不能部署到QNX当成生产安装器。厂商库、固件和开发私钥不在本仓库；开放验签库的来源与许可保留在 `native/vendor/` 和 NOTICE 中。
