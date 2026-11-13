# GNSS 时间采集：Android 侧实现与 Rust 对接说明

| 项目 | 内容 |
|---|---|
| 对应上游设计 | `xmcserverlancher/docs/android-gnss-time-calibration.md`（v1.0 对接草案） |
| 本文范围 | IriX Android 客户端（本仓库）的采集实现、线协议、Rust `xmc_time` 接收端契约 |
| 实现位置 | `app/src/main/java/org/mcfso/irix/gnss/`、`app/src/main/cpp/time_jni.c` |

---

## 1. 本仓库做了什么、不做什么

**做**：

- 采集 GNSS 时间样本（Raw GNSS / GNSS 毫秒时钟 / Location 三档，自动降级）；
- 上报**诚实的** `uncertainty_ns` 和**与测量同帧**的 `mono_ns`；
- 本地诊断判定（供观察采集质量）、JSONL 落盘（24h 测试原始数据）、可选的本机回环 NDJSON 流；
- 通过 JNI 嵌入 `libxmc_time.so`（`TimeNative`），把同一份 `gnss_sample` 直接送入 Rust 卡尔曼滤波；
- 通过 `TimeBridgeClient` + `adb reverse` 把样本送到电脑上的 `xmc_time`。

**不做**：

- 不修改系统时间，也不替代 `xmc_time` 的融合/纪律逻辑；
- 本地判定只是诊断，不是 Rust 侧的接受/拒绝结论，`stepReady` 仅表示“本机连续一致帧数达标”；
- 不负责跨设备时间轴映射（跨设备由接收端按上游 §5.3 处理）。

> 结论：本仓库负责“高质量采集 + 同设备 JNI 送入 `xmc_time`”；真正的时钟纪律仍在 Rust。

---

## 2. 时间轴：本仓库最容易踩的坑

`mono_ns` 使用 `SystemClock.elapsedRealtimeNanos()`（Linux `CLOCK_BOOTTIME`，含休眠），它是**本设备、本次开机**的时间轴：

- **同进程 FFI（Flutter Android 构建路径）**：`mono_ns` 与 `zeitstempel::now_including_suspend()` 同源，可直接用；
- **跨设备（手机 → 桌面 Rust）**：两条 BOOTTIME 原点不同，`mono_ns` **不能**直接与桌面 `zeitstempel` 相减。必须由接收端自行测量到达时刻，按 §5.3 的方式换算，或先在传输层做 RTT 对齐。

`hello` 握手行显式声明了这一点（`mono_axis_scope` / `external_axis_mapping`），避免接收端默认"同轴"。

---

## 3. 三种采集路径与降级

| 模式 | 实现 | API 要求 | 典型 1σ | 说明 |
|---|---|---|---|---|
| `RAW` | `GnssMeasurementsEvent` + `GnssClock` | 24+，同帧配对需 29+ | 0.1~2µs（室外开阔） | 优先路径；无 `fullBiasNanos` 或未知闰秒则放弃该帧 |
| `GNSS_TIME_MILLIS` | `SystemClock.currentGnssTimeClock()` | **29+** | 1~10ms | 公开 API；`LocationManager.getGnssTimeMillis()` 不是公开 API，未使用 |
| `LOCATION` | GPS `Location` 回调 | 23+ | ~10ms 起 | 最终兜底，`Location.time` + `elapsedRealtimeNanos` |

`AUTO` 的降级规则：Raw 有可靠同帧对齐且 2s 内有样本 → 用 Raw；否则用毫秒时钟；再否则用 Location。**任何降级都会在页面上给出中文原因**，不会静默切换。

### 3.1 Raw GNSS 的两条硬约束

1. **历元换算**：`GnssClock` 得到的是 **GPS 时间**（1980-01-06 起），必须
   `unix_ns = gps_ns + 315964800e9 - leap_seconds * 1e9`。
   直接减闰秒会得到 1980 年起的错误绝对值。
2. **同帧时间戳**：只有 API 29+ 的 `GnssClock.hasElapsedRealtimeNanos()` 才提供
   "测量时刻对应的 CPU 单调时间"。没有它时 `mono_ns` 只能取**回调到达时间**，
   回调延迟（0.1~5ms，高负载更大）无法界定——此时样本被标记为
   `alignment = "callback_unbounded"`，`uncertainty_ns` 直接给到 50ms 以上（仅监控），
   `AUTO` 模式会降级而不是假装微秒级。

### 3.2 闰秒与星座

- `leap_seconds` 取 `GnssClock.leapSecond`，**未知时为 `null`**，绝不默认 18；
- 北斗不套用 GPS 的 18s：`bdt_utc_offset_s` 单独字段，当前实现为 `null`（Android 公共 API 不提供）；
- `fix` 来自 NMEA **GSA**（第 3 个字段：1=无定位 / 2=2D / 3=3D），不用卫星数或高度猜测；
- `constellation` 只输出 `gps / bds / galileo / glonass / multi`，QZSS 并入 `gps`。

---

## 4. 线协议

### 4.1 `gnss_sample`（与上游文档 §5.1 一致）

```json
{"op":"gnss_sample","args":{
  "utc_ns":1767225600000000000,
  "mono_ns":5000000000000,
  "uncertainty_ns":800,
  "constellation":"multi",
  "sv_count":14,
  "fix":"3d",
  "leap_seconds":18,
  "source":"raw",
  "clock_discontinuity":3,
  "age_ms":2,
  "alignment":"gnss_elapsed_realtime",
  "cn0_db_hz":44.5,
  "bias_uncertainty_ns":120.0,
  "elapsed_realtime_uncertainty_ns":500.0,
  "drift_ns_per_s":0.7,
  "sdk_int":35,
  "model":"Pixel 8"
}}
```

- 契约字段（Rust 必须解析）：`utc_ns` `mono_ns` `uncertainty_ns` `constellation` `sv_count` `fix` `leap_seconds` `source` `clock_discontinuity` `age_ms`；
- 诊断字段（可忽略）：`alignment` `cn0_db_hz` `bias_uncertainty_ns` `elapsed_realtime_uncertainty_ns` `drift_ns_per_s` `drift_ppb` `latency_ns` `bdt_utc_offset_s` `sdk_int` `model`；
- `leap_seconds` 可能为 `null`（未知）；`uncertainty_ns` 为 1σ，已包含对齐误差。

### 4.2 `gnss_hello`（连接建立首行）

```json
{"op":"gnss_hello","args":{"protocol":1,"app":"IriX-Android","sdk_int":35,
  "raw_supported":true,"mono_axis":"elapsedRealtimeNanos",
  "mono_axis_scope":"device_boot","external_axis_mapping":"required"}}
```

### 4.3 传输通道

| 通道 | 状态 | 说明 |
|---|---|---|
| 同进程 JNI（`TimeNative`） | 已实现 | 加载 `libxmc_time.so` + `libirix_time_jni.so`，每个样本调用 `time_request("gnss_sample", args)`；同一条 BOOTTIME 轴 |
| 本机回环 TCP NDJSON（默认端口 47810） | 已实现，默认关闭 | 仅绑定 `127.0.0.1`；最多 4 个客户端；慢客户端直接断开；单向只读 |
| JSONL 日志 + 分享导出 | 已实现，默认开启 | 8MiB × 8 分片，可导出用于 24h 复盘 |

回环 socket 是**本机同设备**通道；桌面 Rust 无法直连手机的回环地址。桌面校准应使用 JNI 同设备路径，或另做带 RTT 的局域网接收端。

### 4.4 同进程 JNI 接入

- Rust 库：`libxmc_time.so`（arm64-v8a，API 23）放在 `app/src/main/jniLibs/arm64-v8a/`；
- JNI shim：`app/src/main/cpp/time_jni.c`，CMake 见 `app/src/main/cpp/CMakeLists.txt`；
- Kotlin：`org.mcfso.irix.gnss.TimeNative`，`start(statePath)` 初始化，`ingest(argsJson)` 送样本；
- `GnssCalibrationController.consume()` 在本地诊断后调用 `TimeNative.ingest(GnssWire.argsJson(sample))`；
- 构建 Rust 库：`tool/build_xmc_time_android.ps1`（默认 NDK 28、API 23、arm64-v8a）；
- 验证：`adb shell am instrument` 或 `./gradlew :app:connectedDebugAndroidTest` 运行
  `TimeNativeInstrumentedTest`（`self_test` + `now`）。

### 4.5 ADB reverse 时间桥（手机 → 电脑）

同一份样本还会经 USB 送到电脑上的 `xmc_time`：

```powershell
adb reverse tcp:47820 tcp:47820
```

- 电脑 `xmc_time` 监听 `127.0.0.1:47820`（token `irix-adb`），启动时自动开启；
- 手机 `TimeBridgeClient` 在 GNSS 采集开始时连接 `127.0.0.1:47820`，断线每 2s 重连；
- 协议：`gnss_hello` → `gnss_ping`（手机测 RTT）→ `gnss_sample`（带 `rtt_ns`）；
- 电脑用 `rtt_ns/2` 作为 `transport_delay_ns`，再交给 `ingest_gnss`；
- 手机和电脑仍是两条 BOOTTIME 轴，不能直接相减；
- 没有 `adb reverse` 时连接失败是预期行为，不影响手机本地采集；
- 精度受 ADB/USB 调度影响，通常约 **0.2~2ms**，明显优于 Wi-Fi。

> `TimeBridgeClient` 与 `TimeNative` 并行：JNI 校准手机自己的时钟，ADB 桥把同一份
> GNSS 观测送到电脑；两者各自独立校验，互不替代。

---

## 5. Rust `xmc_time` 接收端清单

### 5.1 必做校验（与上游 §5.1 对应）

1. `mono_ns` 与本地单调时钟差 > 2s → 拒绝（**跨设备时先按 §5.3 换算，否则必然全拒**）；
2. `utc_ns` 落在 2020~2200；
3. `uncertainty_ns` 下限 100ns、上限 50ms（超限只记监控，不改写上报值）；
4. `fix != "3d"` 或 `sv_count < 5` → R ×10 或仅监控；
5. `clock_discontinuity` 变化 → 丢弃该帧；
6. 偏移 = `utc_ns - app_clock_at(mono_ns)`，卡尔曼 `R = σ_gnss² + σ_app² + σ_age²`；
7. 与 NTP 偏差 > 5σ 只记录，不立即拨钟；
8. 连续 3 帧一致才允许 step。

### 5.2 必须忽略的字段

`alignment` 等诊断字段只用于日志与问题定位；**不要**据此推断样本质量，质量应以 `uncertainty_ns` 与 `sv_count`/`fix` 为准。

### 5.3 跨设备接入（上游文档未覆盖，务必实现）

手机与桌面不是同一条 BOOTTIME 轴。接收端应：

1. 在**本机**记录样本到达时刻 `t_rx_mono` 与本机 UTC 估计 `t_rx_utc`；
2. 用 `delay ≈ t_rx_utc - (utc_ns + age_ms)` 估计单程传输延迟；
3. `offset = utc_ns + delay - app_clock_at(t_rx_mono)`；
4. `R += (delay_jitter)²`，且 `delay` 为负或 > 2s 时拒绝该帧；
5. 需要亚毫秒时才值得做 ping/pong RTT 对消，否则 LAN Wi-Fi 的抖动会主导误差。

若无法可靠界定传输延迟，**不要**把跨设备 GNSS 当作高权重源。

---

## 6. 权限与运行形态

- `ACCESS_FINE_LOCATION`（必须真的授予，粗定位不算）+ `ACCESS_COARSE_LOCATION`；
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`，服务类型 `location`；
- `POST_NOTIFICATIONS`（33+）：只影响通知可见性，拒绝不阻塞采集；
- 采集只能从可见页面手动开始；无开机自启、无后台自启、无 `ACCESS_BACKGROUND_LOCATION`；
- 不读取网位置/融合位置，不保存坐标。

---

## 7. 验证现状

- 单元测试 52 个（时间换算/历元、校验规则、时钟回归诊断、线协议、σ 动态估计）全部通过；
- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 通过；
- `:app:assembleDebugAndroidTest` 通过（JNI instrumentation 测试已编译）；
- APK 已包含 `lib/arm64-v8a/libxmc_time.so` 与 `libirix_time_jni.so`；
- 桌面 `xmc_time` bridge 的 `hello/ping/sample` 已用本机 TCP 客户端联调通过
  （样本返回 `accepted:true`）；
- Raw GNSS 的硬件 σ 若 >1s（部分机型用 131071/131072s 表示“未知”），
  不再直接当 1σ，也不再固定成 2ms：`GnssSigmaEstimator` 用相邻样本的
  `(utc_n-utc_{n-1})-(mono_n-mono_{n-1})` 的 MAD 动态估计抖动；
  8 帧以内用 20ms 先验，之后收敛到实测值，UI 的“不确定度来源”显示
  `hardware / empirical / prior / monitoring`；
- GNSS 诊断记录的 `rx.unixMs` 已改用 `TimeNative.nowUnixMsOrSystem()`，即校准后的
  应用时钟（库不可用时回退系统时钟）；
- **未做真机验证**：Raw GNSS 的实际精度、回调延迟分布、息屏后回调频率、北斗是否输出，
  以及 `TimeNativeInstrumentedTest` 的 JNI 自检，都需要目标机型实测
  （对应上游文档 §9 的待确认项）。

---

## 8. 测试建议（对应上游 §7）

1. 室外静止，`RAW` 模式采集 ≥10min，看 `sv_count`/`fix`/`uncertainty_ns` 是否稳定；
2. 同一时段与公网 NTP 对比偏移均值与 σ（注意 NTP 自身 ms 级误差）；
3. 把 `uncertainty_ns` 与实际抖动对比，验证是否虚报（这是本模块最关键的诚实性指标）；
4. 关闭 socket 后导出 JSONL，供 Rust 侧离线重放；
5. 室内失锁观察 `AUTO` 是否按预期降级并给出原因。
