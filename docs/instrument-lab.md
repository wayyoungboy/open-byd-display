# 仪表端独立研发实验

目标配置在 `firmware/targets.json`。两车型原生编译/链接已通过，具体仪表硬件、QNX运行或实车刷写仍未验证。当前Python实现是可运行的协议和状态机实验，不是可刷固件。

在仓库根目录配置Python3.12并安装 `firmware/requirements.txt`：

```sh
export PYTHONPATH=firmware
python3 -m openbyddisplay.packages keygen lab-state/keys
mkdir -p lab-state/source
echo 'OpenBYDDisplay development data' > lab-state/source/version.txt
python3 -m openbyddisplay.packages build \
  --source lab-state/source --key lab-state/keys/development-private.pem \
  --target song-plus-dmi-2021 --hardware-id host-lab --version 0.1.0 \
  --output lab-state/song-0.1.0-lab.fup
python3 -m openbyddisplay.service \
  --state lab-state/song --public-key lab-state/keys/development-public.pem \
  --target song-plus-dmi-2021 --hardware-id host-lab --port 47785
```

唐实验使用独立输出包与state目录，target为 `tang-plus-dmi-2021`。不要将实验包用于现成仪表。私钥、包和状态目录被Git忽略，不会放入Releases；签名只在当前配置的信任域有效。

服务提供无账号的info/upload/status/apply/cancel，校验签名、载荷、目标与硬件标识，拒绝低版本。安装只写state目录下不可变generation并发布current.json；之前版本保留。脚本不执行，设备分区不访问，不重启车辆。传输中断、最终回执丢失、重复请求和部分发布有回归测试。

开放Clang/LLD构建已可用。下一阶段完成QNX实际加载与原生服务验证、平台安装后端、真实仪表硬件识别、独立于升级分区的恢复入口及两车型分别验证。只有这些证据齐备后，才可在GitHub发布明确标注对应硬件的可刷包。

新增的[增量事务实验](incremental-install.md)独立管理签名差量、基线和试启动确认；此处原更新服务会拒绝增量包，不会误装成完整代际。
