# 车机App

Android源码、Gradle工程、自启/ADB助手、发布签名配置及APK发布工具均在本目录。包名与稳定发布签名规则不变；目录调整不会要求卸载旧App。

0.1.5 开发源码接通[免 U 盘自研服务升级](../docs/native-update.md)，并包含[氛围灯](../docs/ambient-light.md)和[USB/系统接口](../docs/vehicle-interfaces.md)。尚未发布此版本 Release，也未完成本项目实车验证；全部迁移差异见[清单](../docs/feature-migration.md)。定制开发请联系作者，定制类仪表固件需要收费。

在仓库根目录运行：

```sh
./headunit/gradlew -p headunit :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 headunit/tools/adb_setup.py --serial SERIAL
```

产物为`headunit/app/build/outputs/apk/debug/app-debug.apk`，日常安装使用GitHub Releases中的稳定签名APK。已接入持续视频发送、独立地图/飞屏副地图和本地数据合成；能力和验证边界见[车机投屏](../docs/casting.md)。尚未完成车机/QNX实物验收。仪表端源码和原生构建见[firmware](../firmware/README.md)。
