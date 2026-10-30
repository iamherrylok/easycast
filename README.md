# EasyCast

面向支持安装 APK 的 **Android TV 电视和电视盒子** 的通用视频投屏接收端。最低支持 Android 8.0（API 26），无 Google Play 服务依赖。参考常见投屏软件的使用流程，独立实现。

电视应用列表、启动图块、App 首页和手机搜索到的设备名统一为 **EasyCast**。界面采用奶油色暖色调；Logo 是一座带电视屏幕的小房子，电视启动图块为左侧 Logo、右侧 EasyCast 文字的单行布局，不含副标题。设备名不绑定品牌或电视型号。使用同一安装包覆盖升级会保留已有设备 UUID。

“通用”指不依赖特定电视品牌的实现，并不表示已验证所有电视：其他 Android TV 设备仍需实测；非安卓电视系统不能直接安装此 APK。

## 第一版范围

- UPnP AV / DLNA 风格的 MediaRenderer：SSDP 发现、设备描述、SOAP 播放控制、GENA 状态回传。
- Media3 播放器：HTTP(S) MP4、HLS、DASH 等；实际格式和清晰度取决于电视解码能力及视频源。
- 播放、暂停、停止、点播跳转、App 内音量和静音；直播是否能跳转取决于播放源。
- 电视遥控器操作；显示网络与错误状态；网络地址变化后重建接收服务。
- 稳定设备 UUID，避免每次启动都成为一个新设备。

**当前是前台测试版本：请先在电视打开 App，投屏期间保持 App 在前台。退出、切换其他 App 或电视待机后服务停止。** 不包含 AirPlay 屏幕镜像、华为 Cast、后台常驻、开机自启或付费内容解密。不是经过 DLNA 认证的商用产品，也未宣称兼容全部投屏 App。

**咪咕 iPhone 客户端发现与播放的兼容性待实机验证。** 此前版本已在一台 Android 10 / API 29 电视上安装并启动，确认接收首页正常显示；电脑经局域网 SSDP 成功发现接收端，真实电视的 `GetTransportInfo` 返回 `NO_MEDIA_PRESENT / OK`。这不代表咪咕兼容性或视频解码已经验证。

## 安装与使用

安装包：在 GitHub [Releases](https://github.com/iamherrylok/easycast/releases) 下载 `easycast-1.0.0.apk`，或本地构建后在 `dist/` 中获取。

1. 通过电视允许的 APK 安装方式安装。使用 U 盘时，需要电视上的文件管理器能够打开 APK，并允许该安装来源；具体菜单取决于电视固件。也可在已授权的 ADB 连接上执行：

   ```sh
   adb devices
   adb -s YOUR_TV_SERIAL install -r easycast-1.0.0.apk
   ```

2. 在电视应用列表打开 **EasyCast**，确认显示“接收服务已启动”。
3. 手机与电视连接同一家庭局域网。电视可以使用网线，前提是与手机所在 Wi-Fi 互通。
4. iPhone 设置 → 隐私与安全性 → 本地网络，允许咪咕视频访问。
5. 在咪咕的视频播放页点击 **TV**，查找 **EasyCast**。这不是 iPhone 控制中心的“屏幕镜像”。
6. 先测普通点播视频，再测你常看的直播。记录“搜不到 / 找到但连接失败 / 连接后播放失败”和电视显示的错误名称。

播放时按遥控器返回键停止视频并返回接收首页；再按返回键退出。首页“重启接收服务”会清除现有投屏订阅，手机可能需要重新选择设备。

## 本地构建

需要 JDK 17 或 21、Android SDK Platform 35、Build Tools 34.0.0。项目使用 Gradle 8.9 / AGP 8.7.3，Media3 1.5.1 固定版本。

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/easycast-1.0.0.apk
```

也可用 Android Studio 打开项目，设置本地 SDK 后编译。开发工具和缓存不进入 Git。

正式版签名读取 `signing/keystore.properties`（字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`），该目录不进入 Git。缺少此文件时 release 构建不签名。后续版本必须使用同一签名才能覆盖安装；此前安装过 debug 签名测试版的电视需先卸载再装正式版。

当前工作目录已备有本地构建工具时，可直接运行 `bash scripts/build-local.sh`。需要代理时，将 Gradle 的 `-Dhttps.proxyHost=...` 等参数传给此脚本；项目不保存代理配置。

## 测试与边界

`UpnpTest` 覆盖点播控制、URL/元数据转义、直播跳转限制、参数错误、服务描述一致性、事件内容、发现请求以及回调地址校验。

`RendererServerTest` 启动真实的本地 HTTP 服务，模拟控制端拉取描述、加载并播放视频、查询状态及订阅状态变化；播放器使用替身，不代表解码已通过。

真机验收仍需检查：iPhone 咪咕能否发现、视频能否播放、音画同步、直播、网络断开恢复、退出重进、遥控器操作与播放界面。网络隔离、咪咕专用协议/设备筛选、DRM、需特定请求头或鉴权的视频地址，均可能导致兼容性失败。

服务仅在前台监听选定的局域网 IPv4 地址，使用 UDP 1900 和动态 HTTP 端口。未引入账户、广告或遥测；播放地址不写入日志。与常见 DLNA 接收端一样，同一网络里的设备在 App 打开时可以发送播放控制。

## 代码结构

- `core/Upnp.java`：与 Android 无关的服务描述、控制协议和状态事件。
- `core/RendererServer.java`：局域网发现、HTTP 控制与事件订阅。
- `PlayerAdapter.java`：把协议操作调度到 Media3 的主线程。
- `MainActivity.java`：电视界面、遥控器与前台生命周期、网络变化处理。

## 参考

- [UPnP AV 标准](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver4-and-mediarenderer3/)
- [Android TV 应用要求](https://developer.android.com/training/tv/get-started/create)
- [Media3 播放器](https://developer.android.com/media/media3/exoplayer/hello-world)
