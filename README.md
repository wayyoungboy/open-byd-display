# OpenBYDDisplay

**比亚迪仪表显示与车机互联开源项目。**

> **本项目为测试版本，由个人出于兴趣爱好开发。使用本软件即表示你知悉并自愿承担测试风险。在法律允许的最大范围内，本软件按现状提供，不作任何保证；项目作者、贡献者及相关个人或组织不对可能存在的问题及由此产生的损失承担责任。**

源码、评审、测试构建、问题反馈和软件下载统一通过 GitHub：

- [Releases：APK 与后续更新包](https://github.com/wayyoungboy/open-byd-display/releases)
- [Issues：问题与适配记录](https://github.com/wayyoungboy/open-byd-display/issues)
- [Actions：构建与测试](https://github.com/wayyoungboy/open-byd-display/actions)

支持项目：[爱发电赞助](https://afdian.com/a/wayyoung)。赞助完全自愿，不影响软件功能和更新下载。

## 当前阶段

v0.1.0 是**更新链路研发版**：Android 客户端可选包、预检、流式上传、查询、提交安装和清理任务；电脑实验服务可验签、校验目标、暂存组件并切换实验目录中的版本指针。Android 上传与安装分开操作，不会开机自动刷写，结果不明时不自动重复提交。

本版本没有登录、VIP、商业许可证或 Token 鉴权。更新包签名、摘要、目标车型和硬件校验用于防止篡改及错刷，继续保留。第三方固件自己的授权与信任公钥不由本项目解除；自有签名包只适用于信任对应自有公钥的服务。

**当前没有可交付实车刷写的自研 QNX 固件，也没有完成新 App 的实车出画验收。** `instrument/` 是只监听电脑回环地址的实验服务，不是可以刷入仪表的镜像；不会执行包内 `install.sh`、写车辆分区或修改启动记录。投屏接收、QNX原生更新后端和整车恢复流程仍在研发。

| 研发目标 | 当前状态 |
| --- | --- |
| 21款宋 PLUS DM-i | 已建独立目标配置；仪表硬件、QNX原生编译与实车刷写待验证 |
| 21款唐 PLUS DM-i | 按用户名称建独立配置；准确车型和仪表型号待核实，不能据文件名与宋版互刷 |

## 使用与开发

从 Releases 下载标明测试状态的 APK。包名 `org.openbyddisplay`，与原小灰灰包独立，不需要卸载或清除原 App。只使用与当前仪表匹配且来源明确的更新包；在车辆静止、供电稳定时进行操作。

当前 App 主要用于自研服务。ZIP 预检覆盖六个必要文件、可选 `patch.sv`、ZIP CRC 和 SV 压缩体 MD5；FUP2 预检覆盖头、路径和载荷摘要。车机预检不能代替仪表端验签及硬件适配验证，MD5不作为真实性凭证。

实验包的制作、运行、故障恢复及验证边界见 [实验服务说明](docs/instrument-lab.md)，线协议见 [更新协议](docs/update-protocol.md)。App 主页提供 GitHub Releases 入口，当前通过 GitHub 下载后选择本地文件，不声称已经实现固件自动OTA。

开发需要 JDK17、Android SDK34/build-tools34.0.0、Python3.12。先配置自己的 `local.properties` 和虚拟环境：

```sh
python3 -m pip install -r instrument/requirements.txt
PYTHONPATH=instrument python3 -m unittest discover -s instrument/tests -v
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

发布使用稳定的独立 Android 签名；私钥不入 Git。CI仅发布测试构建，版本标签触发 Releases 工作流。签名配置、版本约束和产物规则见 [GitHub分发](docs/github-distribution.md)。

## 独立性、权利与风险说明

本项目与比亚迪、德赛及其他厂商、商业投屏产品不存在隶属、合作、授权、认证或背书关系；名称仅用于说明研究对象，不授予商标使用权。本仓库只发布自主代码与有权再分发的依赖，不包含第三方 APK、仪表固件、反编译代码、厂商密钥或车辆个人信息。贡献者必须有权提交所提供的内容，不要上传商业许可证或生产签名私钥。

修改车机和仪表可能导致不可启动、显示异常、原车功能受影响、数据或财产损失，并可能影响质保或合规状态。任何支持、适配或测试结论均以明确列出的版本与实际证据为限；“可构建”“收到ACK”“已暂存”不代表车辆可安全刷写或实际出画。

本项目采用 [MIT License](LICENSE)，保留原作者及贡献者版权声明，见 [NOTICE](NOTICE)。
