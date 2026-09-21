# SlackOff — 追剧小铺 + 小说频道

双端（iOS / Android）的追剧与阅读应用。

- **视频**：M3U8 播放器。从后端接口拉取影片列表与播放地址，通过本地回环 HTTP 服务下载、修复「伪装成图片」的 TS 分片，再交给系统播放器，并带一套类 bilibili 的播放器交互与弹幕。
- **小说**：高仿夸克小说的阅读频道。书城 / 书架 / 详情 / 沉浸式阅读器，数据来自本地 `xiaoshuo_server`（笔趣阁系站点的实时解析服务，`:4321`）。

> iOS 纯 SwiftUI、Android 纯 Compose，均无第三方 UI 依赖；iOS 18 / Swift 6，Android minSdk 26。

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [工程结构](#工程结构)
- [架构与数据流](#架构与数据流)
- [接口文档](#接口文档)
- [关键实现](#关键实现)
- [遇到的问题与解决方案](#遇到的问题与解决方案)
- [构建与运行](#构建与运行)
- [测试](#测试)

---

## 功能特性

- 首页视频流（去分组网格）+ 搜索
- 详情页：封面 / 简介 / 演员 / 选集（选中态高亮）
- 播放器：
  - 内嵌播放 + 竖屏全屏 + 横屏全屏
  - 类 bilibili 控制条（单击显隐、4s 自动隐藏、状态栏联动）
  - 手势：横滑调进度、左半边竖滑调亮度、右半边竖滑调音量、双击播放/暂停
  - 静音、倍速、上一集/下一集（单集自动隐藏）、锁定
  - 选集 / 倍速 / 弹幕设置从右侧抽屉滑出（暗黑风）
- 弹幕：自动匹配番剧源、颜色 / 字号 / 速度 / 显示区域 / 透明度 / 防重叠
- 选集状态按视频记忆（`UserDefaults` 持久化，跨启动保留）
- 小说频道（高仿夸克）：
  - 书城：搜索胶囊 / 金刚区 9 入口 / Banner 轮播 / 强力推荐 / 排行榜（8 榜切换）/ 分类精选 / 最近更新
  - 书架：「继续阅读」大卡片 + 三列文字封面网格 + 精确到页的阅读进度（章内页序比例）
  - 详情：元信息（字数 / 连载状态）/ 简介折叠 / 完整目录（1920 章一次拉全、渐进展示、正倒序）
  - 阅读器：仿真翻页 / 覆盖滑动 / 上下滚动三种模式；字号 / 行距 / 5 种纸色 / 夜间 / 亮度 / 目录抽屉 / 章节进度条拖拽跳章；相邻章后台预取
  - 文字封面：源站封面域名在设备上直连不通，统一用稳定哈希（FNV-1a）取色的渐变书封，双端同色板
  - 服务地址可配置（模拟器 `127.0.0.1`、真机填局域网 IP），数据源可切换（auto / 三源）

---

## 技术栈

| 分类 | 技术 |
| --- | --- |
| UI | SwiftUI（`@Observable`、`@Bindable`）、UIKit 桥接（`AVPlayerLayer`、旋转、亮度） |
| 播放 | AVFoundation（`AVPlayer` / `AVPlayerItem` / `AVPlayerLayer`） |
| 网络 | `URLSession` + 自建回环 HTTP 服务（裸 BSD socket） |
| 并发 | Swift 并发（`async/await`、`Task`、`Actor/MainActor` 默认隔离） |
| 排版 | iOS CoreText（`CTTypesetter` 逐行测量）/ Android `StaticLayout`，共用纯函数切页层 `PageSplitter` |
| 翻页 | iOS `UIPageViewController(.pageCurl)` / Android `Canvas.drawBitmapMesh` 网格卷曲 |
| 工程 | XcodeGen（`project.yml` → `.xcodeproj`，同步文件夹自动收录源文件） |
| Android | Jetpack Compose（BOM 2024.09）、kotlinx.serialization、OkHttp、navigation-compose、Coil |
| 语言 | Swift 6.0 / iOS 18.0；Kotlin / Android minSdk 26 |
| 测试 | Swift Testing（`@Test` / `#expect`）、JUnit4（Android 纯逻辑）、XCUITest 真实截图 |

> `SWIFT_APPROACHABLE_CONCURRENCY` / `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`：UI 相关类型默认跑在主线程，省去大量标注。

---

## 工程结构

```
SlackOff/
├── SlackOffApp.swift              # @main 入口
├── Models/
│   ├── VideoModels.swift          # 视频列表/详情/播放信息模型
│   └── DanmakuModels.swift        # 弹幕番剧/评论模型
├── Services/
│   └── APIClient.swift            # VideoService / DanmakuService
├── Support/
│   ├── APIConfig.swift            # 后端地址、UA、Referer、JSON 解码器
│   ├── AppDelegate.swift          # 屏幕方向锁定
│   └── EpisodeStore.swift         # 选集状态持久化
├── Playback/
│   ├── PlaybackEngine.swift       # 播放引擎（AVPlayer 封装、音量、静音）
│   ├── PlayerModel.swift          # 一集视频的 UI 状态机（播放/弹幕/倍速）
│   ├── MediaFetcher.swift         # 下载 + 防盗链请求头多候选重试
│   ├── M3U8Parser.swift           # 解析 m3u8 / 解码 ETS 重定向
│   ├── HLSMediaSource.swift       # 单条流本地映射：下载→改写→修复→缓存
│   ├── HLSLocalServer.swift       # 127.0.0.1 极简 HTTP 服务（BSD socket）
│   └── TSSegmentRepair.swift      # 识别/裁剪「伪装成图片」的 TS
├── Danmaku/
│   ├── DanmakuSettings.swift      # 弹幕样式（@Observable）
│   └── DanmakuView.swift          # 弹幕渲染 + 防重叠轨道分配
├── Novel/
│   ├── PageSplitter.swift         # 切页纯函数（双端同语义，可单测）
│   ├── TextPaginator.swift        # CoreText 逐段逐行测量 → 分页
│   ├── PageCurlReader.swift       # UIPageViewController(.pageCurl) 桥接
│   ├── NovelReaderModel.swift     # 阅读器状态机（目录/正文/跨章/预取/进度）
│   ├── NovelReaderView.swift      # 阅读器容器 + 上下菜单 + 进度条
│   ├── ReaderCatalogDrawer.swift  # 目录抽屉（右滑暗黑面板）
│   ├── ReaderSettingsPanel.swift  # 字号/行距/纸色/翻页/亮度
│   ├── NovelTextCover.swift       # 文字封面（FNV-1a 稳定取色）
│   ├── BookstoreView.swift        # 书城（轮播/金刚区/榜单/分类精选）
│   ├── BookshelfView.swift        # 书架（继续阅读 + 三列网格）
│   ├── NovelBookDetailView.swift  # 详情 + 目录
│   ├── NovelSearchView.swift      # 搜索 + 历史 + 空态推荐
│   ├── NovelBookListViews.swift   # 分页/榜单/本地三种列表页
│   └── NovelSettingsView.swift    # 服务地址/数据源/书架管理
└── UI/
    ├── RootTabView.swift
    ├── VideoHomeView.swift
    ├── VideoDetailView.swift
    ├── PlayerView.swift           # 内嵌播放器
    ├── FullscreenPlayerView.swift # 全屏（自适应横竖屏 + 手势 + 抽屉）
    ├── PlayerLayerView.swift      # AVPlayerLayer 桥接
    ├── DanmakuSettingsView.swift  # 弹幕设置抽屉内容
    ├── Components/VideoSlider.swift # 细进度条
    └── Theme.swift
SlackOffTests/
├── SlackOffTests.swift            # 解析/TS 修复/防盗链/端到端播放
├── RenderCheck.swift              # 播放页渲染截图
├── NovelTests.swift               # 小说模型/URL/切页/分页/书架/设置（真实响应 fixture）
└── NovelRenderCheck.swift         # 阅读器 CoreText 直绘 + 封面/书架离屏渲染（含像素断言）
SlackOffUITests/
├── FullscreenEpisodeSwitchUITests.swift
└── NovelChannelUITests.swift      # 模拟器真实运行截图（书城/详情/阅读器/菜单）
android/app/src/main/java/com/slackoff/app/
├── model/NovelModels.kt           # @Serializable 响应模型（与 iOS 一一对应）
├── network/NovelService.kt        # 端点封装（60s 超时、无 Referer）
├── novel/
│   ├── PageSplitter.kt            # 切页纯函数（与 iOS 同语义同用例）
│   ├── TextPaginator.kt           # StaticLayout 整章排版 + clip/translate 分页绘制
│   ├── PageCurlView.kt            # drawBitmapMesh 卷曲翻页（20×20 网格 + 阴影）
│   └── NovelReaderViewModel.kt    # 阅读器状态机
├── support/
│   ├── NovelSettingsStore.kt      # 服务地址/阅读器设置（SharedPreferences + StateFlow）
│   ├── BookshelfStore.kt          # 书架与进度
│   └── NovelSearchHistory.kt      # 搜索历史
└── ui/novel/                      # 书城/书架/详情/搜索/列表/阅读器/设置各 Screen
android/app/src/test/java/com/slackoff/app/
├── novel/PageSplitterTest.kt      # 与 iOS PageSplitterTests 同一组用例
├── novel/NovelServiceUrlTest.kt
├── novel/NovelSupportTest.kt
└── model/NovelModelsTest.kt
```

---

## 架构与数据流

```
VideoHomeView ──GET /api/home──▶ [VideoCard]
      │ 点击
      ▼
VideoDetailView ──GET /api/detail/{slug}──▶ VideoDetail(episodes)
      │ 点某集
      ▼
PlayerView / PlayerModel ──GET /api/play/{slug}?episode=N──▶ PlayInfo.streams
      │ 取最后一个有 url 的 stream
      ▼
PlaybackEngine.load(streamURL, headers)
      │ MediaFetcher 下载 m3u8 → M3U8Parser.parse → [M3U8Segment]
      │ probe() 嗅探首个分片真实类型（TS / 伪装TS / fMP4 / 图片）
      ▼
HLSLocalServer.mount()  → 返回 http://127.0.0.1:{port}/hls/{token}/0.m3u8
      │
      ▼
AVPlayer ◀── 本地服务（按需：下载原始分片 → 改写 → TSSegmentRepair 修复 → 回吐）
```

弹幕数据流：

```
PlayerModel.loadDanmaku()
   └─ DanmakuService.searchAnime(title) ─▶ anime
        └─ bangumi(animeId) ─▶ 集数 → episodeId
             └─ comments(episodeId) ─▶ [DanmakuComment]
                  └─ DanmakuView 渲染（按 DanmakuSettings 样式）
```

---

## 接口文档

后端地址（`APIConfig.swift`）：

- 视频：`https://tanlang008-up14load.hf.space`
- 弹幕：`https://jokkad-danmu-api.hf.space`
- 弹幕 token：`123456`（拼接在弹幕路径前）
- 统一请求头：`Referer: https://www.4kvm.org/`、桌面版 `User-Agent`

### 视频接口

#### 1. 首页列表

```
GET /api/home
```

响应：

```json
{
  "code": 0, "message": "ok",
  "items": [
    {
      "slug": "ch4i26m8o",
      "title": "怒之杀",
      "url": "https://www.4kvm.org/play/ch4i26m8o",
      "cover": "https://...",
      "rating": "8.0",
      "remark": null,
      "tag": null,
      "section": "抓特务"
    }
  ]
}
```

#### 2. 搜索

```
GET /api/search?q={keyword}
```

响应结构与 `/api/home` 一致。

#### 3. 详情

```
GET /api/detail/{slug}
```

响应（snake_case → 服务端 JSONDecoder `.convertFromSnakeCase`）：

```json
{
  "code": 0, "message": "ok",
  "detail": {
    "slug": "ch4hog6c3", "title": "师兄太稳健",
    "type_name": "剧情", "area": "大陆", "release": "2026",
    "episode_count": 30,
    "description": "...", "actor": "...",
    "episodes": [
      { "index": 1, "name": "1", "url": "...", "dataid": "37864" }
    ]
  }
}
```

#### 4. 播放信息（拿到 m3u8 地址）

```
GET /api/play/{slug}?episode={n}
```

响应：

```json
{
  "code": 0, "message": "ok",
  "play": {
    "slug": "ch4hog6c3", "dataid": "37864", "episode": 1,
    "quality": "1080",
    "streams": [
      { "mtype": "m3u8", "bitrate": 2160000, "title": "4K", "is_vip": true, "locked": true, "url": null, "headers": {} },
      { "mtype": "m3u8", "bitrate": 1080, "title": "1080p", "is_vip": false, "locked": false,
        "url": "https://oss.douyinbit.com/m3u8/xxx.m3u8",
        "headers": { "Referer": "...", "User-Agent": "..." } }
    ]
  }
}
```

> App 取 `streams` 里**最后一个** `url != nil` 的流，并把 `headers` 透传给后续 m3u8 / 分片下载。

### 弹幕接口

路径统一为 `{danmuBase}/{token}{path}`，即 `https://jokkad-danmu-api.hf.space/123456/api/v2/...`。

#### 搜索番剧

```
GET /api/v2/search/anime?keyword={title}
```

#### 番剧详情（含集数）

```
GET /api/v2/bangumi/{animeId}
```

#### 某集弹幕

```
GET /api/v2/comment/{episodeId}?format=json
```

响应中的每条弹幕：

```json
{ "cid": 42, "p": "12.50,4,16777215", "m": "来了" }
```

`p` 为 `时间(秒),类型,颜色`、`m` 为文本；`类型`：`4` 底部、`5` 顶部、其余为滚动。

### 小说接口

本地 `xiaoshuo_server`（`http://127.0.0.1:4321`，Swagger 在 `/docs`）。它把笔趣阁系站点 HTML **实时解析**成 JSON，不落库；三个源：`bqg99`（顶点，全能力）、`blqvdu`（顶点镜像，无搜索）、`biquge365`（新笔趣阁，无搜索、分类可深翻 1769 页）。

| 端点 | 说明 |
| --- | --- |
| `GET /api/home?source=` | 首页聚合：hot_books（有封面）/ recommend / latest_updates / category_blocks |
| `GET /api/categories?source=` | 分类字典（slug ↔ 中文名） |
| `GET /api/categories/{slug}?page=&source=` | 分类书籍列表（`has_more` 驱动翻页） |
| `GET /api/ranks?board=&source=` | 排行榜（8 榜 × 15 条） |
| `GET /api/full?page=&source=` | 全本小说 |
| `GET /api/search?kw=&page=&source=` | 站内搜索（仅 bqg99 支持；`auto` 会自动落到它） |
| `GET /api/book/{book_id}?source=` | 书籍详情（字数 / 状态 / 章节数 / 首章） |
| `GET /api/book/{book_id}/chapters?offset=&limit=&source=` | 目录（`limit=0` 一次全量，1920 章 0.07s） |
| `GET /api/chapter/{book_id}/{chapter_id}?source=&clean_ads=` | 章节正文（纯文本 `\n` 分段 + prev/next） |
| `GET /api/ping` / `GET /api/cache/stats` / `GET /api/sources` | 存活 / 缓存统计 / 源能力矩阵 |

**source 策略**（重要）：`book_id` 与分类 slug 都是源站私有、跨源不通用。列表类接口可传 `auto`（服务端按健康度 + 优先级故障转移），但 `/api/categories` 与 `/api/ranks` 的响应**不带** source，必须显式指定 —— 书城用 `/api/home` 响应里的「活跃源」；详情 / 目录 / 正文一律回传资源自带的 source。

**错误体**：正常错误是 `{"detail":"重试 4 次后仍失败: PoolTimeout | https://..."}`（字符串），FastAPI 校验错误是 `{"detail":[{"msg":...}]}`（数组），两端都解析成可读文案展示在错误页。

---

## 关键实现

### 1. 本地回环 HLS 服务（`HLSLocalServer` + `HLSMediaSource`）

AVPlayer 需要真实 `http` 地址才走完整 HLS；又无法自定义请求头。于是自建只监听 `127.0.0.1` 的极小 HTTP 服务：

- 用 **裸 BSD socket**（`socket/bind/listen/accept`），因为 `NWListener` 在 iOS 沙盒里会因 `SO_NECP_LISTENUUID` 收不到连接。
- 每条流一个 `token`，路由 `/hls/{token}/{id}.{ext}`。
- `HLSMediaSource` 负责：下载原始资源 → 改写给 m3u8（URI 指向本地）→ TS 修复 → 磁盘缓存（最多 160 个文件，按写入顺序淘汰）。
- 本地路径必须带正确后缀（`.ts/.m4s/.mp4`），因为 AVFoundation 按 URL 后缀选解复用器。

### 2. TS 分片修复（`TSSegmentRepair`）

结构：`[假图片头 + 0xFF 填充] + [真 TS：0x47 + 188 字节/包] + [0x00 填充/IEND]`。

- 扫描 `0x47` 同步字节，用「连续 4 个 188/192/204 对齐包」确认包长。
- 优先认 **PAT 包（PID == 0）** 作为起点，避免被假图头里碰巧能成链的 `0x47` 欺骗。
- 从起点向后走完所有同步包，中途撞到填充就截断尾。

### 3. 防盗链重试（`MediaFetcher`）

各家图床 CDN 的 Referer ACL 相反（icve 带站点 Referer 会 403，超星不带 403），按顺序重试：

1. `Referer = {资源自身 scheme}://{host}/`
2. 不带 `Referer`
3. 接口下发的 `headers`

并拦截 HTML 错误页（有些 CDN 403 也吐 `<!doctype` 页面）。

### 4. 手势与 HUD（`FullscreenPlayerView`）

单一个 `DragGesture(minimumDistance: 12)` 按起始方向 + 起始 x 坐标判定：

- `|dx| > |dy|` → seek
- 垂直且起点在左半边 → 亮度（真机 `UIScreen.main.brightness`；模拟器用黑色遮罩模拟）
- 垂直且起点在右半边 → 音量（`AVPlayer.volume`）

拖动时显示浮层 HUD（进度 / 亮度% / 音量%）。

### 5. 弹幕防重叠（`DanmakuView`）

按时间排序可见弹幕，维护每条轨道的「忙到何时」，新弹幕优先落到已空闲轨道；轨道纵向范围按「显示区域」百分比收缩；颜色 / 字号 / 速度 / 透明度由 `DanmakuSettings` 控制。

### 6. 屏幕方向（`AppDelegate` / `OrientationLocker`)

- 进横屏全屏：锁 `.landscape` 并请求横屏。
- 进竖屏全屏：`.all`（允许自由旋转）。
- 退出全屏：`lockPortrait()` 强制回竖屏。

---

## 遇到的问题与解决方案

| # | 问题 | 现象 | 解决方案 |
| --- | --- | --- | --- |
| 1 | M3U8 里 TS 分片后缀是 `.jpg/.png/.bmp` 且带假图片头 | AVPlayer 按文件头当图片 → `-1016` | `TSSegmentRepair` 定位真正 TS 区间、裁掉伪装头尾 |
| 2 | PNG `tEXt("TS_RAW")` 里有个假 `0x47` 恰好落在真 PAT 前 188B | PAT 被挤成第二个包 → 有声无画面 | 优先认 PAT（PID=0）；参考 https://zhuanlan.zhihu.com/p/619829579 |
| 3 | 各图床防盗链 Referer 规则相反 | 带/不带 Referer 都会 403 | `MediaFetcher` 多候选请求头依次重试 + 识别 HTML 错误页 |
| 4 | AVPlayer 不能自定义请求头，`AVAssetResourceLoader` 拦分片失败 | 分片 `-12881` | 自建 `127.0.0.1` HTTP 服务喂流 |
| 5 | `NWListener` 收不到连接 | iOS 沙盒 `SO_NECP_LISTENUUID` 失败 | 改用裸 BSD socket |
| 6 | 客户端断开后写 socket 触发 SIGPIPE | 进程被杀 | `signal(SIGPIPE, SIG_IGN)` + `SO_NOSIGPIPE` |
| 7 | Swift `"\r\n"` 是一个 Character，`split("\n")` 切不开 | m3u8 被当一行 → 0 分片 | 用 `split(whereSeparator: \.isNewline)` |
| 8 | `/ets/{token}/{base64}` 重定向 | 拿不到真实地址 | `M3U8Parser.resolve` 解 base64 尾巴 |
| 9 | 本地服务路径后缀被改掉 | AVFoundation 选错解复用器 | 按原始 URL 后缀重建本地后缀 |
| 10 | 模拟器上亮度手势无可见变化 | `UIScreen.main.brightness` 不动 | 模拟器用黑色遮罩，真机写系统亮度 |
| 11 | 退出全屏仍停在横屏 | 回到详情页还是横的 | `lockPortrait()` 强制竖屏 |
| 12 | 弹幕同轨道重叠 | 视觉混乱 | 轨道「忙到何时」贪心分配（防重叠） |
| 13 | 选集状态退出即丢 | 重进回到第 1 集 | `EpisodeStore` + `UserDefaults` 按 slug 持久化 |
| 14 | 系统 `Slider` 圆钮过大 | 不符合审美 | 自绘 `VideoSlider`（3pt 轨道 + 10pt 圆点） |
| 15 | `CTTypesetterSuggestLineBreak` 返回的是**从 startIndex 起的长度**，不是绝对 index | 当成 index 用后 `end == start`，触发防死循环保护 → 每行只排一个字（截图里像竖排） | `end = start + suggestedLength`；渲染检查截图一眼看出 |
| 16 | `CTTypesetter` 断行**不考虑** `firstLineHeadIndent`（缩进只在 CTFramesetter 整页排版生效） | 段首缩进 2 字符完全不生效 | 手动模拟：首行断行用 `width - indent`，绘制时 x 加回 indent，测量与渲染保持一致 |
| 17 | `@Observable` 类里 `Optional` 属性在 Swift 中自动初始化为 nil，init 里再赋 nil 属于**第二次赋值** | `brightness = nil` 触发 didSet → persist()，把「全默认值快照」写回磁盘，覆盖了正要读取的正确配置 | init 全程用 `isRestoring` 标志抑制 persist；并加回归测试断言「恢复后磁盘字节不变」 |
| 18 | Swift **raw string**（`#"""`）不做转义处理 | 生成测试 fixture 时把 JSON 的 `\n` 多加了一个反斜杠 → 正文变成字面 `\n`，切不出段落 | raw string 里直接放 JSON 原文 |
| 19 | `UIHostingController` + `drawHierarchy` 在单元测试宿主里截出来是**纯空白图**（7 张图字节数完全相同） | 「截图成功」的断言形同虚设 | 阅读器改走 CoreText 直绘（`UIGraphicsImageRenderer` + `layout.draw`）；纯 SwiftUI 部分用 `ImageRenderer`；并加**像素级断言**（与纸色不同的像素占比），空白图直接失败 |
| 20 | `ScrollView` / `LazyVGrid` 在 `ImageRenderer` 下拿不到 viewport | 离屏渲染整页书架 = 空白 | 把书架格子 / 继续阅读卡片抽成独立组件，测试用非 lazy 的 `VStack`+`HStack` 组合出图；真实布局交给 XCUITest 截图 |
| 21 | 仓库里的 `gradle-wrapper.jar` 残缺（缺 `GradleWrapperMain.class`） | `./gradlew` 直接 ClassNotFoundException | 用本地 Gradle 8.9 重新生成 wrapper（保留腾讯镜像 distributionUrl） |
| 22 | Navigation Compose 的路径段为空时路由匹配不上 | 作者/分类为 null 会生成 `novel/book/a/b/c//` | 可空参数一律走**可选查询参数**（`?author=&category=`） |
| 23 | 章节进度条 `Slider(steps:)` 传 1920 章 | 生成 1918 个刻度，滑动直接卡死 | 连续滑动 + 松手取整跳章 |
| 24 | 源站封面挂在 `www.bqg99.cc` 域下，设备直连不通 | 大面积裂图 | 统一文字封面（FNV-1a 稳定取色），`cover` 字段只解析不使用 |

---

## 构建与运行

### 环境

- Xcode 26+（或支持 Swift 6 / iOS 18 的 Xcode）
- 可选：`brew install xcodegen`

### 生成工程

工程由 `project.yml`（XcodeGen）驱动，源文件用「同步文件夹」，新增/删除 `.swift` 会被自动纳入，无需手改 pbxproj：

```bash
cd /Users/mac/Documents/ChatGPT/slack_off
xcodegen generate   # 若 .xcodeproj 已存在可直接跳过
```

### 命令行构建 / 运行

```bash
# 构建
xcodebuild build -project SlackOff.xcodeproj -scheme SlackOff \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'

# 安装 + 启动（模拟器）
xcrun simctl install booted \
  ~/Library/Developer/Xcode/DerivedData/SlackOff-*/Build/Products/Debug-iphonesimulator/SlackOff.app
xcrun simctl launch booted com.slackoff.app
```

或在 Xcode 里打开 `SlackOff.xcodeproj` 直接 ⌘R。

#### Info.plist 与 ATS

小说服务是明文 HTTP。loopback（`127.0.0.1`）本身被 ATS 豁免，但**真机填局域网 IP 不是 loopback**，所以 `project.yml` 里用 XcodeGen 的 `info:` 生成了根目录 `Info.plist`，写入 `NSAppTransportSecurity.NSAllowsLocalNetworking = true`，并把原先 4 个 `INFOPLIST_KEY_*`（启动屏 / 场景清单 / 间接输入 / 屏幕方向）等价迁移了进去 —— 有真实 plist 后这些 build setting 会被 Xcode 忽略，不迁移会导致旋转与启动屏回归。

### Android

```bash
cd android
./gradlew assembleDebug          # 构建
./gradlew testDebugUnitTest      # 单元测试（分页/URL/封面/进度/模型解码）
./gradlew installDebug           # 装到已连接设备/模拟器
```

Android 侧 `AndroidManifest.xml` 已有 `usesCleartextTraffic="true"`，无需额外 ATS 配置。

---

## 测试

Swift Testing 单元/集成测试（`SlackOffTests/`）：

```bash
xcodebuild test -project SlackOff.xcodeproj -scheme SlackOff \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

覆盖内容：

- `M3U8Parser`：ETS 重定向、CRLF 播放列表
- `TSSegmentRepair`：伪装图片 TS 裁剪、假 0x47 / PAT 优先级
- `MediaFetcher`：防盗链请求头候选顺序
- 端到端：真实 m3u8 → 本地服务 → AVPlayer；接口 → 引擎 → 播放
- `RenderCheck`：播放页渲染截图