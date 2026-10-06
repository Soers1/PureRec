# PureRec

开源免费的安卓录屏应用。**无广告、无悬浮窗、不联网、不上传、不收集任何数据。**

用 MediaCodec + OpenGL ES 自己拼的一条录制管线，画质和体积都比系统自带录屏好控制。

## 功能

- **画质**
  - 默认使用**设备物理最大分辨率**，并按设备 H.264 编码器的真实能力自动收敛（不会因为超出编码器上限而黑屏 / 报错）
  - 可选 1080P / 720P / 480P 降档
  - 帧率 24 / 30 / 60
  - 码率三档：自动 / 省空间 / 高画质
  - H.264 硬编 + MP4 封装
- **声音**
  - 麦克风录制（可关）
  - **系统内部声音**（录制游戏 / 视频 / 音乐声，需要 Android 10 及以上，可关）
  - 两个开关同时打开时会自动混成一条音轨，不会出现两条声音打架
  - **麦克风增强**：自动增益（AGC）+ 降噪（NS），人声更清楚、底噪更低
- **画面**
  - 前置 / 后置摄像头画中画（可关），右下角小窗
  - OpenGL 合成，不额外占用 CPU
- **没有悬浮窗**
  - 录制控制全部走状态栏常驻通知，不遮挡画面、不会被录进视频
- **省心录制**
  - **录制前倒计时** 3 / 5 / 10 秒，来得及切窗口
  - **录制中随时暂停 / 继续**：画面停住、声音静音，恢复后接着录，出来还是一个完整文件
  - **摇一摇停止**：录的时候不想切回 App，晃一下手机就收工（灵敏度三档，可关）
  - **定时自动停止**：15 分钟 / 30 分钟 / 1 小时 / 2 小时，到点自己保存收工
  - **低电量自动停止**：电量掉到阈值自动收尾，充电时不触发
  - **录制中保持屏幕常亮**，免得录到一半屏幕自己黑了
- **随手开录**
  - **下拉快捷磁贴**：把 PureRec 加进控制中心，随手一点就开录；正在录时点它直接停
- **视频管理**
  - 列表带缩略图、时长、体积、分辨率、日期
  - 内置播放器
  - 重命名 / 删除 / 分享 / 导出到系统相册
  - **录完自动存进相册**（Android 10+，不需要额外权限）
  - **文件名前缀自定义**，默认 `PureRec_20260101_120000.mp4`
- 深色 UI，Material 3
- **关于作者**页：作者自述、联系方式（QQ 可一键复制）、版本与许可信息

## 环境要求

- Android 8.0 (API 26) 及以上
- 系统内部声音需要 Android 10 (API 29) 及以上
- 编译需要 JDK 17 + Android SDK 35

## 自己编译

```bash
git clone <你的仓库地址>
cd PureRec
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

如果没有 `local.properties`，Android Studio 会自动生成；命令行编译请手动创建：

```
sdk.dir=/你的/Android/Sdk 路径
```

## 签名

仓库里 `app/keystore/purerec.jks` 是一把**示例签名**（口令 `purerec123`，别名 `purerec`），
放进来只是为了让 clone 下来就能直接编出可安装的 APK。

要正式发布请换成自己的签名，在项目根目录建 `keystore.properties`：

```properties
storeFile=/绝对路径/your.jks
storePassword=xxxx
keyAlias=xxxx
keyPassword=xxxx
```

## 项目结构

```
app/src/main/java/com/purerec/
├── MainActivity.kt              入口、权限流程、导航、录制前倒计时
├── RecTileService.kt            下拉快捷设置磁贴
├── core/
│   ├── RecordingConfig.kt       录制参数
│   ├── RecordingService.kt      前台服务（录制控制 + 状态栏通知 + 摇一摇 / 定时 / 电量守护）
│   ├── ScreenRecorder.kt        一次录制的总编排
│   ├── ScreenRenderThread.kt    OpenGL ES 合成（屏幕 + 摄像头小窗）
│   ├── VideoEncoder.kt          H.264 硬编
│   ├── AudioRecorder.kt         麦克风 / 系统声音采集、混音、AAC 编码、AGC / NS
│   ├── MuxerWrapper.kt          MediaMuxer 线程安全包装
│   ├── CameraController.kt      Camera2 采集
│   ├── ShakeDetector.kt         摇一摇检测
│   ├── GalleryExporter.kt       录完自动导入系统相册
│   ├── VideoUtils.kt            编码器能力探测与分辨率收敛
│   └── RecPrefs.kt / RecordingState.kt / DisplayHelper.kt
├── ui/
│   ├── screens/                 Compose 界面（录制 / 视频库 / 关于作者）
│   └── theme/                   配色与字体
└── util/VideoLibrary.kt         文件列表 / 缩略图 / 重命名 / 导出
```

录制链路：

```
MediaProjection → VirtualDisplay ─┐
                                  ├─ OpenGL ES 合成 → MediaCodec(H.264) ─┐
Camera2 ─────────────────────────┘                                       ├→ MediaMuxer → MP4
麦克风 AudioRecord ─┐                                                    │
内部声音 (PlaybackCapture) ─┴─ 混音 → MediaCodec(AAC) ───────────────────┘
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `RECORD_AUDIO` | 麦克风收音（可以不开） |
| `CAMERA` | 摄像头画中画（可以不开） |
| `POST_NOTIFICATIONS` | 显示录制中的状态栏通知 |
| `FOREGROUND_SERVICE(_MEDIA_PROJECTION)` | 录屏必须的前台服务 |
| `WRITE_EXTERNAL_STORAGE` | 仅 Android 9 及以下「导出到相册」时使用 |

没有联网权限，装完可以断网跑。

## 已知限制

- 录制过程中旋转屏幕，画面方向不会实时跟着切换（开始录制时的方向为准）。建议先摆好方向再开始。
- 部分机型不支持 60fps 的高分辨率录制，`VideoUtils` 会自动降级到编码器支持的组合。
- 系统内部声音只能录到**允许被捕获**的应用（应用可以在自己的音频属性里声明不允许捕获）。
- 麦克风增强（AGC / NS）依赖机型是否提供对应的音频效果，拿不到时会自动跳过，不影响录制。
- 摇一摇停止需要设备有加速度计；没有的话开关会自动失效。

## 关于作者

我姓辛，高三。

说不清具体是从哪天开始的。大概是某次晚自习结束，回家瘫在床上刷手机，忽然发现自己连点开一个 App 的力气都没有。

高三这一年，日子过得又快又黏——试卷、排名、倒计时，还有医院给的那张单子，上面印着"抑郁症"三个字。我倒没打算拿它当借口，课照上，题照做，日子照过。只是总有那么些时候，书翻不进去，觉也睡不着，手边总得有点别的事做。

PureRec 就是从那种"闲"里长出来的。闲得发慌，又不想让自己一直空着，就找点正经事干。想录点东西，商店里翻了一圈——要么广告弹到让你想把手机扔了，要么录三分钟就提示「升级会员解锁时长」。烦了，那就自己写。

从啃文档到第一个能出画面的版本，磨了两个多月，全是挤出来的零碎时间。代码谈不上漂亮，但每一行我都知道它在干什么——不联网、不上传、不留存。因为我自己也讨厌那套东西。

- **微信**：`16653653008`（暂时无法添加好友）
- **QQ**：`2803790685`
- 其他联系方式以后再说

要是它帮上你了，那这两个月就值了。

## License

MIT
