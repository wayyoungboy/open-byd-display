# 车机App

Android源码、Gradle工程、自启/ADB助手、发布签名配置及APK发布工具均在本目录。包名与稳定发布签名规则不变；目录调整不会要求卸载旧App。

在仓库根目录运行：

```sh
./headunit/gradlew -p headunit :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 headunit/tools/adb_setup.py --serial SERIAL
```

产物为`headunit/app/build/outputs/apk/debug/app-debug.apk`，日常安装使用GitHub Releases中的稳定签名APK。此App目前为更新链路客户端，自研视频编码发送尚未接入。仪表端源码和原生构建见[firmware](../firmware/README.md)。
