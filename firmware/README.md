# 仪表固件研发

本目录包含仪表端原生源码、无SDK构建工具、目标配置、签名组件打包和安装恢复实验。路线为原机系统＋自研服务＋增量包。**原生程序已能编译和链接，QNX实际运行和实车刷写尚未验证。**

| 目录 | 内容 |
| --- | --- |
| `native/core/` | C99 LHF2/CRC/NAL校验 |
| `native/qnx66/` | 独立入口、运行探针、mm-renderer/Screen/mdevpush适配、单会话TCP播放器 |
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

原生播放器与平台边界见[解码显示](../docs/native-media.md)，签名增量包与试启动流程见[增量安装](../docs/incremental-install.md)，构建步骤见[无SDK工具链](../docs/qnx66-open-toolchain.md)。Python安装器仅写显式实验目录，不能部署到QNX当成生产安装器。第三方库、固件和开发私钥不在本仓库。
