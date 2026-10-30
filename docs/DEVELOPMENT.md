# EasyCast 开发文档

面向想自己编译、修改或排查兼容性问题的开发者。普通用户请看 [README](../README.md)。

## 技术概览

EasyCast 是一个 UPnP AV / DLNA 风格的 MediaRenderer（媒体渲染器），独立实现，参考常见投屏软件的使用流程。

- **发现**：SSDP（UDP 1900）广播与应答，设备描述 XML。
- **控制**：SOAP 播放控制（AVTransport、RenderingControl）。
- **状态回传**：GENA 事件订阅。
- **播放**：Media3 / ExoPlayer，支持 HTTP(S) MP4、HLS、DASH 等；实际格式和清晰度取决于电视解码能力及视频源。
- **操作**：播放、暂停、停止、点播跳转、App 内音量和静音；直播能否跳转取决于播放源。
- **网络**：仅在前台监听选定的局域网 IPv4 地址，HTTP 端口动态分配；网络地址变化后自动重建接收服务。
- **设备标识**：首次启动生成稳定的设备 UUID 并保存，同一签名覆盖升级会保留它。

最低支持 Android 8.0（API 26），不依赖 Google Play 服务。未引入账户、广告或遥测，播放地址不写入日志。与常见 DLNA 接收端一样，App 打开时同一网络里的设备都可以发送播放控制。

## 代码结构

| 路径 | 说明 |
| --- | --- |
| `app/src/main/java/.../core/Upnp.java` | 与 Android 无关的服务描述、控制协议和状态事件 |
| `app/src/main/java/.../core/RendererServer.java` | 局域网发现、HTTP 控制与事件订阅 |
| `app/src/main/java/.../PlayerAdapter.java` | 把协议操作调度到 Media3 的主线程 |
| `app/src/main/java/.../MainActivity.java` | 电视界面、遥控器与前台生命周期、网络变化处理 |
| `scripts/GenerateBanner.java` | 重新生成电视启动图块 `banner.png` |
| `scripts/PromoVideo.java` | 渲染 20 秒竖屏推广视频 |

## 本地构建

需要 JDK 17 或 21、Android SDK Platform 35、Build Tools 34.0.0。项目使用 Gradle 8.9 / AGP 8.7.3，Media3 1.5.1 固定版本。

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/easycast-1.0.0.apk
```

也可用 Android Studio 打开项目，设置本地 SDK 后编译。开发工具和缓存不进入 Git。

当前工作目录已备有本地构建工具（`.tools/`）时，可直接运行 `bash scripts/build-local.sh`。需要代理时，将 Gradle 的 `-Dhttps.proxyHost=...` 等参数传给此脚本；项目不保存代理配置。

### 签名

正式版签名读取 `signing/keystore.properties`（字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`），该目录不进入 Git。缺少此文件时 release 构建不签名。

后续版本必须使用同一签名才能覆盖安装。此前安装过 debug 签名测试版的电视需先卸载再装正式版（会生成新的设备 UUID）。

### 推广素材

```sh
java scripts/GenerateBanner.java                       # 重新生成 banner.png
java scripts/PromoVideo.java png out/ 2.5 9.6 19.5     # 导出指定秒数的静帧
java scripts/PromoVideo.java | ffmpeg -f rawvideo -pix_fmt bgr24 -s 1080x1920 -r 30 -i - \
  -c:v libx264 -pix_fmt yuv420p dist/easycast-promo-vertical.mp4
```

## 测试

- `UpnpTest`：点播控制、URL/元数据转义、直播跳转限制、参数错误、服务描述一致性、事件内容、发现请求以及回调地址校验。
- `RendererServerTest`：启动真实的本地 HTTP 服务，模拟控制端拉取描述、加载并播放视频、查询状态及订阅状态变化。播放器使用替身，不代表解码已通过。

## 兼容性与实测记录

“通用”指不依赖特定电视品牌的实现，并不表示已验证所有电视；非安卓电视系统不能直接安装此 APK。本项目不是经过 DLNA 认证的商用产品。

已验证：

- Android 10 / API 29 电视上安装启动正常，接收首页显示正常。
- 电脑经局域网 SSDP 能发现接收端，真实电视的 `GetTransportInfo` 返回 `NO_MEDIA_PRESENT / OK`。

待实机验证：iPhone 咪咕视频能否发现与播放、音画同步、直播、网络断开恢复、退出重进、遥控器操作与播放界面。

可能导致兼容性失败的因素：网络隔离（访客 Wi-Fi、AP 隔离）、特定 App 的专用协议或设备筛选、DRM、需要特定请求头或鉴权的视频地址。

反馈问题时，请注明电视型号与系统版本、投屏 App、现象（搜不到 / 找到但连接失败 / 连接后播放失败）以及电视上显示的错误名称。

## 参考

- [UPnP AV 标准](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver4-and-mediarenderer3/)
- [Android TV 应用要求](https://developer.android.com/training/tv/get-started/create)
- [Media3 播放器](https://developer.android.com/media/media3/exoplayer/hello-world)
