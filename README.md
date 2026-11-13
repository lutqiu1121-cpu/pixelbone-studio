# 像素骨骼 · PixelBone Studio Android 版

给像素画摆骨骼、做帧动画。**纯本地处理、不联网、不上传** —— 一个单文件网页，套上一层极薄的 WebView 原生壳，打成可以直接装到手机上的 APK。

<img src="docs/icon-512.png" width="120" align="right" alt="图标">

> 网页原稿：`web/index.html`（单文件、零依赖、字节未改动）
> 安装包：Releases 里的 `PixelBone-<版本>.apk`

## 为什么还要套个壳

网页版在手机浏览器里能用，但有三件事体验不好，壳就补这三件事：

| 网页里做不到 / 别扭的 | 原生壳怎么补 |
| --- | --- |
| 「导入 PNG」要靠浏览器文件选择器，横屏下弹窗小、多选别扭 | `WebChromeClient#onShowFileChooser` + `EXTRA_ALLOW_MULTIPLE`，一次选多张、可反复追加 |
| 点「导出图集+JSON」后，`blob:` 链接在 WebView 里点了没反应 | 注入脚本接管这次点击，把 PNG / JSON 字节经 `AndroidSave` 桥交给原生，直接写进系统「下载/像素骨骼/」 |
| `confirm()` 在 WebView 里默认被静默吞掉（导出前的尺寸确认会失效） | 实现 `onJsAlert / onJsConfirm / onJsPrompt`，用原生对话框弹出 |

另外三件顺手的事：全屏沉浸横屏（画布铺满、系统栏滑出后自动收回）、返回键弹「退出会丢失工程」确认、屏幕常亮。

编辑器的算法全在网页里：FK 层级矩阵、锚点整数像素、边缘拉伸、帧硬切换、图集打包、Godot JSON。壳不实现任何绘制或动画逻辑。

## 下载与安装

1. 到 Releases 下载 `PixelBone-1.0.apk`；
2. 手机上点开安装（需要允许「安装未知来源应用」）；
3. 打开「像素骨骼」，横屏操作；导出后文件落在 **下载/像素骨骼/**。

支持 Android 7.0（API 24）及以上；targetSdk 34。

## 功能

- **骨骼节点**：点「加节点」在画布落点、`attachToSelected` 自动挂到当前节点下连成骨架，父节点改旋转时子节点按 FK 跟随，并自动补偿局部坐标使世界位置不跳
- **挂载素材 + 自定义锚点**：多选透明 PNG；点素材进入锚点拾取层（可缩放），点中哪个像素，该像素左上角就是旋转中心，**整数像素坐标**，旋转不会半像素发虚
- **节点参数**：旋转 −360°~360°、缩放 0.1×~4×、局部 X/Y 整数位移、换父节点、移除图片、删节点
- **边缘拉伸**（防穿模补缝）：逐像素 Alpha 遍历，把上下左右相邻的非透明像素**原样复制**到透明像素，硬扩 0–4 px，离屏 Canvas 预处理后缓存，渲染与导出共用同一份结果
- **关键帧时间轴**：加帧 / 覆盖当前帧 / 删帧 / 单帧时长 16–5000 ms，**帧间硬切换、不做补间**（贴合像素游戏手感），可播放预览
- **视图操作**：单指拖拽、双指捏合缩放（0.25×~32×）、屏幕旋转后自动复位视图
- **主题色**：顶部取色器实时改写 `--primary`，UI 与画布强调色全局换肤
- **导出**：包围盒 → 横向 Sprite Sheet PNG（透明背景，逐帧整数裁剪）+ Godot 4 `AtlasTexture` JSON，可一键同时导出

整个渲染管线关闭 `imageSmoothingEnabled`，DPR 上限 3，放大后仍是锐利像素格子。

## 隐私

- 清单里**没有 `INTERNET` 权限**，从系统层面杜绝联网
- 页面里没有任何外部请求（无 CDN、无字体、无统计），断网可用
- 导入的图片只在内存和画布里过一遍，不落盘（除了你主动导出的图集与 JSON）

## 构建

无需 Gradle / Android Studio / 网络，六步工具链直接出包：

```bash
./build.sh                                  # → dist/PixelBone-1.0.apk
VERSION_NAME=1.1 VERSION_CODE=2 ./build.sh  # 改版本号
```

依赖（脚本里有默认路径，可用环境变量覆盖）：

| 变量 | 用途 |
| --- | --- |
| `JAVA_HOME` | JDK 17（`javac` / `keytool` / `apksigner` 需要） |
| `ANDROID_HOME` / `BUILD_TOOLS` | SDK 与 build-tools 目录（`d8`、`apksigner.jar`） |
| `AAPT2` | **aarch64 版** aapt2（build-tools 里带的是 x86_64 主机版，手机上跑不了） |
| `ANDROID_JAR` | 平台 `android.jar` |
| `KEYSTORE` / `KS_PASS` / `KEY_ALIAS` | 签名密钥；默认 `build/pixelbone.jks`，首次构建自动生成 |

构建过程：`scripts/IconGen.java` 现画图标 → `scripts/inject_shim.py` 生成 `android/assets/index.html`（注入下载桥，幂等）→ `javac` 编译 → `d8` 转 dex → `aapt2` 编译并链接资源（assets 直接进包，`--version-code/--version-name` 在链接期写入清单）→ 合入 `classes.dex` 并自检 `resources.arsc` 必须是 **STORED 且 4 字节对齐**（targetSdk 30+ 的硬要求）→ `apksigner` 签 v2 + v3。

> 注：`zipalign` 在本机是空壳（build-tools 里只有 x86_64 版），所以对齐靠 aapt2 自身的 `resources.arsc` 对齐 + 构建脚本里的断言兜底；APK 内没有被压缩的原生库，其余条目不需要额外对齐。

图标是标准库现画的：主题蓝渐变圆角方块（`res/drawable/ic_launcher_fg.xml` 提供 v26+ 自适应前景的白色骨形），5 档密度 PNG 由 `IconGen` 4× 超采样生成，无需 Pillow / SVG 工具。

## 目录结构

```
build.sh                          一键打包（六步，含对齐断言）
web/index.html                    网页原稿（单文件、零依赖，不改一个字节）
scripts/inject_shim.py            web/index.html → android/assets/index.html 并注入 blob 下载桥
scripts/IconGen.java              图标生成（AWT，无第三方素材）
android/AndroidManifest.xml       包名 com.pixelbone.studio，横屏全屏，无 INTERNET 权限
android/src/com/pixelbone/studio/MainActivity.java
                                  薄壳：WebView + 文件选择 + blob 落盘桥 + JS 对话框 + 返回键确认
android/res/                      图标、主题、字符串
docs/icon-512.png                 README 用图标预览
```

## 已知限制

- 工程状态（节点、帧、导入的素材）**只存在内存里**，退出或进程被杀即丢失；网页版本身没有工程保存/载入，壳也不擅自发明格式
- 导出走 `AndroidSave` 桥，一次只落一个文件；「图集+JSON」会连写两个文件，两条 Toast 分别提示
- 需要真实安装才能验证的一次性动作（安装器、首次授予「安装未知来源」）不在自动化验证范围内；页面侧已在同引擎 Chromium 里冒烟过：`state` 初始化、canvas 全屏铺满、DPR 3、网格与节点渲染正常

## 许可

MIT，见 [LICENSE](LICENSE)。网页原稿版权归原作者。
