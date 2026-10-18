# GitHub开发与分发

正式入口为 `wayyoungboy/open-byd-display`。不另建下载站、账号系统或许可证服务器。

- 普通改动使用feature分支和PR；CI运行Python/Kotlin测试、Android Lint并上传开发APK artifact。
- 初始源码导入后，发布采用 `v<versionName>` 标签；标签必须与app/build.gradle.kts一致。Release工作流从该提交重新测试、构建、验签，发布APK、SHA256和release-manifest.json。
- GitHub原生源码压缩包用于源码分发。固件包产出后仍通过Releases发布，命名包含车型、硬件、版本与测试状态；未产出或未通过硬件验证时不发布占位“可刷”镜像。
- APK使用独立、稳定的Android发布签名，不复用个人默认debug签名。Actions从仓库Secrets读取ANDROID_KEYSTORE_BASE64、ANDROID_STORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD；缺失则发布失败，不悄悄切换签名。私钥留在仓库外备份，不进入commit/日志/Release资产。
- 发布还会核对 `config/android-release-certificate.sha256` 中的公开证书指纹，签名不符则拒绝分发。fork自行发布时应使用自己的包名、密钥和指纹。
- 自研组件的Ed25519密钥与Android签名不同；开发密钥只用于电脑实验。当前workflow不自动发布实验FUP包到面向用户的latest Release。
- 发布不可覆盖既有版本资产；如修复内容，升版本、经PR检查后创建新标签。初始版本提交与发布记录可以直接追溯到GitHub提交与Actions run。

当前车机App内的“GitHub发布与下载”按钮打开官方Releases。它不执行自动APK安装或无人确认的固件apply。

## 可选赞助入口

GitHub赞助按钮和README均指向维护者提供的[爱发电主页](https://afdian.com/a/wayyoung)。`.github/FUNDING.yml` 使用 `custom` 配置该公开链接；变更赞助地址时经PR同步更新两处入口。赞助完全自愿，不作为使用功能或下载更新的条件。配置格式见 [GitHub官方说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository)。
