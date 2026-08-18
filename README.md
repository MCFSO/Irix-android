# IriX — Android 客户端

[![CI](https://github.com/MCFSO/Irix-android/actions/workflows/ci.yml/badge.svg)](https://github.com/MCFSO/Irix-android/actions/workflows/ci.yml)

IriX 的 Android 客户端：管理 MCSM 风格节点的服务器管理工具，支持实例生命周期、文件管理、容器环境、资源监控。

配套的服务端节点守护进程（IriX-Node，纯 Go 标准库实现）见 [github.com/MCFSO/IriX-Node](https://github.com/MCFSO/IriX-Node)。

## 功能

- **概览**：节点系统信息、CPU / 内存 / 磁盘使用率、网络吞吐、实例统计；15 秒自动轮询刷新
- **实例管理**：列表、启动 / 停止 / 重启 / 强杀、控制台命令下发、日志拉取（支持自动刷新）、配置编辑（启动命令、工作目录、编码、自动启动/重启）、新建 / 删除实例
- **文件管理**：基于实例工作目录的浏览（面包屑导航）、新建目录 / 文件、重命名、删除、文本在线编辑、直连下载（系统 DownloadManager）、直连上传（multipart）
- **容器环境**：运行时信息（Docker / Bastille）、容器列表与控制（启动 / 停止 / 重启 / 强杀 / 删除）、新建容器（镜像、命令、端口映射、数据卷、环境变量、重启策略、资源限制）
- **节点管理**：多节点增删改、一键切换选中节点、API Key / 配对码配置、节点配置本地持久化（DataStore）

## 技术栈

| 层 | 选型 |
| --- | --- |
| UI | Jetpack Compose + Material 3（含 adaptive 导航套件，手机底部栏 / 平板侧边栏自适应） |
| 架构 | MVVM：Compose UI + ViewModel（`StateFlow`）+ Repository + Retrofit |
| 网络 | Retrofit + OkHttp + kotlinx-serialization（统一 `{status, data, time}` 响应封装） |
| 认证 | OkHttp 拦截器统一注入 `apikey`（查询参数 + `X-Api-Key` 头） |
| 存储 | DataStore Preferences（节点配置 JSON 序列化） |
| 构建 | Kotlin 2.2 / AGP 9.3 / Gradle 9.5 |

## 环境要求

- JDK 17+（建议 21）
- Android SDK：`compileSdk 37`，`minSdk 23`（Android 6.0+），`targetSdk 37`
- 无任何原生代码（无 NDK），兼容全部 ABI

## 构建

```powershell
# Debug APK
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# Release APK（需自行配置签名）
.\gradlew.bat :app:assembleRelease
```

使用 Android Studio 打开项目根目录即可直接运行。

## 使用

1. 在「节点」页添加节点：填入名称与地址（如 `http://192.168.1.5:12346`），可选填配对码 / API Key
2. 选中节点后，「概览」页展示实时监控数据
3. 在「实例」页新建实例（启动命令、工作目录），点击实例进入详情：控制台、配置、信息三个标签页
4. 「容器」页管理容器运行时；「文件」页从实例进入其工作目录

### 网络策略说明

节点通常运行在局域网，默认使用明文 HTTP。`network_security_config.xml` 对明文流量全局放行（系统网络安全配置不支持网段匹配，无法只放行私有网段），因此公网强制 HTTPS 由应用层 `NetworkPolicy` 双重拦截保证：

- 节点编辑保存时校验：`http://` 仅允许回环 / 私有 / 链路本地地址与 `localhost`，公网 IP 与域名必须使用 `https://`
- `ApiFactory` 请求构建时再次拦截（覆盖历史存量数据）

## 项目结构

```
app/src/main/java/org/mcfso/irix/
├── MainActivity.kt            # 导航宿主：NavigationSuiteScaffold + NavHost + 全局 Snackbar
├── IrixApplication.kt         # Application + AppContainer（轻量手写 DI）
├── data/
│   ├── api/                   # Retrofit NodeApi、ApiFactory（认证拦截器）、ApiResult 封装
│   ├── model/                 # 全部数据模型（MCSM 风格响应体）
│   ├── NetworkPolicy.kt       # 明文 HTTP 访问策略（公网强制 HTTPS）
│   ├── NodeStore.kt           # DataStore 节点配置 CRUD
│   ├── NodeRepository.kt      # 仓库层（统一 apiCall 错误包装）
│   └── FileTransfer.kt        # 直连下载（DownloadManager）/ 上传（OkHttp multipart）
└── ui/
    ├── overview/              # 概览：系统信息 + 使用率 + 轮询
    ├── instances/             # 实例列表 / 详情（控制台·配置·信息）/ 新建
    ├── files/                 # 文件浏览 / 在线编辑 / 上传下载
    ├── containers/            # 容器列表 / 新建容器
    ├── nodes/                 # 节点管理
    ├── navigation/            # 顶部导航目的地 + 路由
    ├── common/                # 通用组件（Snackbar 助手、状态文案、格式化）
    └── theme/                 # Material 3 主题（含 Android 12+ 动态取色）
```

## 兼容性

- **系统版本**：Android 6.0（API 23）~ 最新；`targetSdk 37` 适配最新平台行为
- **架构**：纯 JVM 字节码，arm64-v8a / armeabi-v7a / x86 / x86_64 全部兼容
- **权限**：仅 `INTERNET` + `ACCESS_NETWORK_STATE`，无运行时权限弹窗
- **屏幕**：自适应导航套件（手机底部导航栏，平板 / 折叠屏侧边栏）

## 许可证

[MIT](LICENSE) © 2026 MCFSO
