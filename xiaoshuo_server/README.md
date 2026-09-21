# 小说聚合 API · xiaoshuo_server

把笔趣阁系小说站的 HTML 页面**实时解析**成结构化 JSON 接口，FastAPI 自动生成 Swagger / ReDoc 文档，本地端口 **4321**。

不落库、不预抓取：请求到来时才回源，配 TTL 内存缓存降压。

---

## 一、站点调研与选型

共探测约 40 个笔趣阁系域名（DNS → TCP → TLS → HTTP → 页面结构五级筛选）。

### 1.1 淘汰情况

| 状态 | 站点 |
|---|---|
| 连接超时/拒绝 | biquge.la、xbiquge.so、biqugen.com、ddxsku.com、ibiquges.com、biquge.tv、paoshu8.com、shuhaige.net、biquge8.cc、xbiqugu.la、bqg5200.com、mangg.net、biquge5200.cc、biquge99.net、ibiquge.net、biqugu.la、dingdiann.com、bjxml.com、biqu5200.net、xswang.com、bqg5.cc、bqzhh.com |
| DNS 解析失败 | bqxs.cc |
| 403 / 反爬 | beqege.cc、69shuba.cx（301→403）、biqooge.com |
| JS 指纹跳转（需真实浏览器） | 69shu.pro、bqg70.com |
| 已停站 | shuquge.org（"Sorry, the website has been stopped"） |
| **不稳定，弃用** | **xshuquge.net（书趣阁）**：curl 可用且内容最新，但 Python httpx 下 HTTPS **15/15 全部超时** |

### 1.2 首轮入选源的实测对比（09-07）

3 轮并发压测（每轮覆盖首页/分类/排行/全本/搜索/详情/正文）：

| 指标 | **bqg99.cc** 顶点小说网 | **biquge365.net** 新笔趣阁 | xshuquge.net 书趣阁 |
|---|---|---|---|
| 成功率 | **21/21 (100%)** | 9/9 (100%) | 0/15 (0%) |
| p50 延迟 | **541 ms** | 530 ms | 超时 |
| 平均延迟 | 2267 ms（含一次 6.4s 慢响应） | 1162 ms | — |
| 平均响应体 | 42.9 KB | 27.1 KB | — |
| 编码 | UTF-8 | UTF-8 | UTF-8 |
| 内容新鲜度 | 更新至 09-07（当天） | 更新至 09-07（当天） | 09-07 12:49 |

> **次日复测（09-08）**：bqg99 一度直连与代理**双双不可达**（数小时后自行恢复），暴露出单源依赖风险；
> 同时发现 xshuquge.net 依旧不可用，而 **blqvdu.cc（顶点小说网镜像）与 bqg99 是同一套模板**且服务端渲染完整，遂补入为第三源。

### 1.3 第二轮筛选（09-08，bqg99 临时宕机后补测）

| 站点 | 结果 | 判定 |
|---|---|---|
| **blqvdu.cc** 顶点小说网镜像 | 与 bqg99 **同模板**（`div.up` s1~s5、`div.wrap.rank`、`div.listmain dl`、`div#content`、`og:novel:*` 全一致）；正文服务端渲染；目录单页 **8684 章**；有排行 `/top/`（10 榜）、全本 `/full.html` | ✅ **入选为第三源** |
| bqg788.cc / bqgam.cc | 详情页同构（1069 章），但**正文页只返回「加载中……」1227B（JS 异步加载）**，且无任何搜索端点 | ❌ 最关键的正文章节拿不到 |
| ddyueshv.cc | 服务端渲染、`#list` 7455 章 | 备选（未接入） |
| bqg99.com / bqg128.com / bige3.cc | 返回 JS 指纹跳转页（1052B）或 noindex 停放页（6990B） | ❌ |
| dingdianxs.cc / bqg9.cc | 403 | ❌ |
| bqg99.net/.xyz/.org、biquge365.com/.cc、xbiquge.cc、bqg5200.com、bqg888.com、bqg.cc | 连接失败 | ❌ |

**踩坑记录（blqvdu）**：
1. **编码不是 UTF-8**——源站 header 声明 `gb2312`、meta 声明 `gbk`，强制 utf-8 会全站乱码，
   连「下一章」文字都匹配不上导致上下章导航失效。已改用超集 `gb18030`。
2. **正文段落是转义的 HTML 实体**（`&lt;p&gt;…&lt;/p&gt;`）并夹带 `<script>app2();</script>` 广告注入，
   直接 `get_text()` 会在正文里留下字面 `<p>`。已抽出共用 `extract_content()`：去 script/style → `<br>`/`<p>` 转换行 → `html.unescape()` → 检测到还原出的真标签则二次解析 → 清广告。三个源统一受益。
3. **首页有 `display:none` 隐藏块**，内含 `/73560_73560376/`《雪中悍刀行》电视剧 之类伪书籍链接，会污染列表，已按祖先 style 过滤。

### 1.4 结论

- **访问速度最快 + 功能最全 → `bqg99.cc`（顶点小说网），设为主源（priority=10，默认）**
  首页 / 分类 / 排行 / 全本 / **站内搜索** / 详情 / 目录 / 正文 全套齐备；
  **目录单页全量返回**（《牧神记》1920 章、《秦沉陆天雪》4891 章，一次请求拿全，无需翻页）；
  页面自带 `og:novel:*` 结构化元数据（作者/分类/状态/字数/更新时间/最新章节），解析最稳。
- **书籍最全（可浏览深度最大）→ `biquge365.net`（新笔趣阁），设为备源（priority=20）**
  分类页有**真实深度分页**：玄幻魔法 **1769 页 × 24 本 ≈ 4.2 万本**，远超主源每类固定 30 条；
  代价：**无站内搜索**（capabilities.search=false）、目录在独立页 `/newbook/{id}/`。
- **第三源 `blqvdu.cc`（priority=15）**：与主源同模板、正文服务端渲染、目录单页 8684 章，作为主源宕机时的等价替身。
- **三源并用 + 健康度感知路由**（详见第七节）：`source` 默认 `auto`，
  列表类接口按「健康源优先」逐个故障转移；id 类接口（详情/目录/正文）因 book_id 源站私有、跨源不通用，取当前健康且优先级最高的源。

---

## 二、爬虫解析映射表

| 能力 | **bqg99.cc** 顶点小说网 | **blqvdu.cc** 顶点镜像（同模板） | **biquge365.net** 新笔趣阁 |
|---|---|---|---|
| 编码 | UTF-8 | **gb18030**（源站 gb2312/gbk） | UTF-8 |
| 首页 | `/` → `div.hot`/`div.r.bd`/`div.up`/`div.type.bd div.block` | `/` → 同左（另需过滤 `display:none` 伪链接） | `/` → `ul.qiangtui`(强推)/`ul.gengxin`(更新)/各 `h2` 区块 |
| 分类字典 | 7 slug：xuanhuan…mm | 9 slug：1~8,10 | 8 slug：1~8 |
| 分类列表 | `/{slug}/` → `div.l.bd ul li`（span.s1~s5）**无翻页** | `/class/{slug}_1.html` → `div.up`/`div.l.bd ul li`（span.s1~s5）**无翻页** | `/sort/{slug}_{page}/` → h2「全部XX小说」父容器 `li`（span.name/jie/zuo/time），`div.page` 解析「(第1/1769页)」 |
| 排行榜 | `/rank.html` → `div.wrap.rank` 内 8×`div.block.bd` | `/top/` → `div.wrap.rank` 内 **10**×`div.block.bd` | 无独立排行页 → 并发抓 8 个 `/sort/{n}_1/`，取侧栏 `ul.bangdan`「收藏排行」聚合 |
| 全本 | `/quanben/` | `/full.html` | `/full/`（100 本） |
| 搜索 | `/s.php?ie=utf-8&q={kw}` → `div.so_list.bookbox`，最多 100 条，**按(书名,作者)去重镜像** | ❌ 无（404） | ❌ 无 |
| 书籍 id 形态 | `/book/{id}/` | `/{catId}_{bookId}/` | `/book/{id}/` |
| 书籍详情 | `div.info`(`h1`/`div.cover img`/`div.small span` 作者·分类·状态·字数·更新时间) + `div.intro` + `og:novel:*` | `h1` + `div.intro` + 封面 img + `og:novel:*` | `h1` + `div.xiangqing`(`div.zhutu img`/`div.xinxi span.x*`) |
| **目录** | `/book/{bid}/` → `div.listmain dl dd a`（**单页全量**，按 cid 去重保序） | `/{bid}/` → `div.listmain dl` **按 `dt` 分段**（「最新章节」12 条是重复块，「正文卷」才是全量）→ 取最大段 | `/newbook/{bid}/` → h2「《X》全部章节」父容器 `ul.info li a`（单页全量） |
| **章节正文** | `/book/{bid}/{cid}.html` → `div#content`，导航 `div.page_chapter a` | `/{bid}/{cid}.html` → `div#content.showtxt`，导航同上 | `/chapter/{bid}/{cid}.html` → `h1` + `div#txt`，导航同上 |

正文处理链（三源共用的 `extract_content()`）：
`<script>/<style>` 剔除 → `<br>` 与 `</p>` 转换行 → `get_text()` → `html.unescape()`
→ 若还原出真标签（blqvdu 的 `&lt;p&gt;`）则**二次 BeautifulSoup 解析** → 压缩空白/全角空格、去空行
→ `strip_ads()` 按行剔除广告语（域名、`m.xxx.cc 手机版`、`N秒记住`、`无弹窗` 等 14 条保守正则，仅删 <120 字的明显广告行）。
`content_html` 始终保留**未加工的原始 HTML 片段**；`clean_ads=false` 可跳过广告过滤。

---

## 三、接口清单

**15 个接口 / 16 个响应 Schema / 6 个标签**，全部带中文 summary + description + 参数示例。
交互式文档：<http://127.0.0.1:4321/docs>（Swagger UI，可直接试调）、`/redoc`、`/openapi.json`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 服务信息与端点索引 |
| GET | `/api/sources` | 数据源清单与能力矩阵 |
| GET | `/api/sources/health` | 实时连通性探测（状态码 + 延迟 ms） |
| GET | `/api/home` | 首页聚合：热门 / 强推 / 最近更新 / 分类块 |
| GET | `/api/categories` | 分类字典（slug ↔ 中文名，是否可翻页） |
| GET | `/api/categories/{slug}` | 分类下书籍列表（`page`） |
| GET | `/api/ranks` | 排行榜（`board` 子串过滤） |
| GET | `/api/full` | 全本小说列表（`page`） |
| GET | `/api/search` | 站内搜索（`kw`，`page`） |
| GET | `/api/book/{book_id}` | 书籍详情（含章节总数、首章） |
| GET | **`/api/book/{book_id}/chapters`** | **完整目录**（`offset`/`limit` 本地分页） |
| GET | **`/api/chapter/{book_id}/{chapter_id}`** | **章节正文** + 上下章导航（`clean_ads`） |
| GET | `/api/cache/stats` | 缓存条目数 / 命中率 / 抓取器参数 |
| DELETE | `/api/cache` | 清空缓存 |
| GET | `/api/ping` | 存活探测（不发起上游请求） |

通用约定：
- 所有接口接受 `source`，**默认 `auto`**：`bqg99` / `blqvdu` / `biquge365` / `auto`。
  - 列表类（首页·分类·排行·全本·搜索）：按健康度顺序**逐个故障转移**，全失败才 502 并附各源错误。
  - id 类（详情·目录·正文）：book_id 跨源不通用，`auto` 取**当前健康且优先级最高**的源。
- `book_id`、`chapter_id` **一律为字符串**——源站 id 可能含前导零（`/chapter/78201/02622105.html`）
  或含下划线（blqvdu 的 `4_4903`），且**跨源不通用**，请沿用列表/搜索返回的同一 `source`。
- 状态码：上游 404 透传 404；重试仍失败 502；源不支持该能力 400；未知源/未知分类 404；参数校验失败 422。

### 典型调用链

```
GET /api/search?kw=凡人修仙传          → items[0] = {source:"bqg99", book_id:"1012236742"}
GET /api/book/1012236742?source=bqg99  → chapter_count=2562, status=完结
GET /api/book/1012236742/chapters?source=bqg99&offset=0&limit=50
GET /api/chapter/1012236742/{cid}?source=bqg99   → content + next_chapter_id（顺着 next 即可连续阅读）
```

---

## 四、健康度感知路由（自动容灾）

第二轮调研时发现主源 bqg99 会**整站临时不可达数小时**（直连与代理同时失败），单源依赖不可接受。
因此加了一层零额外开销的健康度跟踪：

- 每次回源**成功或失败都会登记**到 `_health[source_id] = (ts, ok)`，TTL 默认 300s（`XS_HEALTH_TTL`）。
- `ordered_sources()` 排序：**TTL 内已知健康 → 状态未知 → 已知故障**，同组内按 priority。
  于是坏源不会被每个请求反复重试（否则每请求白等 4 次退避 ≈ 9s），TTL 过期后自动重新试探一次。
- `first_healthy()` 为 id 类接口挑默认源：优先用缓存的健康状态；缓存为空/全过期才并发探测一次（每 TTL 周期最多一次）。
- `/api/sources/health` 主动探测三源并回填健康度；`/api/cache/stats` 里的 `source_health` 可查看当前判定与记录年龄。

**效果：主源宕机或恢复都无需重启服务，也不需改代码。** 实测 bqg99 宕机期间 `/api/home` 自动落到 blqvdu；恢复后自动切回。

---

## 五、运行

```bash
cd /Users/mac/Documents/ChatGPT/xiaoshuo_server
pip3 install -r requirements.txt   # 本机已具备全部依赖
./start.sh                         # → http://127.0.0.1:4321（推荐，自动带代理）
# 或
python3 run.py                     # 直连启动；源站直连不通时会全部 502
```

> **必须带代理启动**：抓取层是 `trust_env`（只读环境变量代理，**不读 macOS 系统代理**），
> 而 `bqg99.cc` / `biquge365.net` 直连经常不通。`start.sh` 默认导出
> `XS_PROXY=http://127.0.0.1:10808`（可用外部环境变量覆盖，`XS_NO_PROXY=1` 强制直连）。
> 判断有没有踩这个坑：`GET /api/cache/stats` 里 `entries=0` 且 `misses` 一直涨，
> 就是回源全失败。
>
> 常驻后台（脱离终端会话，避免关终端/会话回收把服务带走）：
>
> ```bash
> nohup ./start.sh > /tmp/xiaoshuo_server.log 2>&1 &
> ```

- Swagger UI: <http://127.0.0.1:4321/docs>
- ReDoc: <http://127.0.0.1:4321/redoc>
- OpenAPI JSON: <http://127.0.0.1:4321/openapi.json>

冒烟测试（打通全部接口并断言关键字段）：

```bash
python3 scripts/smoke_test.py
```

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `XS_PORT` / `XS_HOST` | 4321 / 0.0.0.0 | 监听端口与地址 |
| `XS_RETRIES` | 4 | 上游失败重试次数（指数退避 0.3→2.0s + 抖动） |
| `XS_TIMEOUT` | 15 | 单请求超时（秒），连接超时固定 8s |
| `XS_CONCURRENCY` | 8 | 上游最大并发（同时是连接池大小） |
| `XS_PROXY` | 空 | 显式代理，如 `http://127.0.0.1:10808` |
| `XS_NO_PROXY` | 0 | 置 1 强制直连，忽略一切代理 |
| `XS_TTL_HOME/RANK/CATEGORY/FULL` | 600 | 列表类缓存秒数 |
| `XS_TTL_SEARCH` | 300 | 搜索缓存秒数 |
| `XS_TTL_BOOK/CHAPTERS` | 21600 | 详情与目录缓存秒数（6h） |
| `XS_TTL_CHAPTER` | 86400 | 章节正文缓存秒数（24h，正文不变） |
| `XS_HEALTH_TTL` | 300 | 源健康度记录有效期（秒），决定坏源多久被重新试探 |
| `XS_RELOAD` / `XS_LOG_LEVEL` | 0 / info | 开发热重载与日志级别 |

### 目录结构

```
xiaoshuo_server/
├── app/
│   ├── main.py             # FastAPI 应用：15 路由、异常映射、auto 故障转移、CORS
│   ├── models.py           # 16 个 Pydantic 响应模型（统一 schema，与源站无关）
│   ├── client.py           # 抓取层：连接复用 / UA 池 / 指数退避重试 / TTL 缓存 + 防击穿锁 / 代理策略
│   └── sources/
│       ├── base.py         # Source 抽象基类 + 共用清洗（clean / clean_intro / extract_content / strip_ads）
│       ├── bqg99.py        # 主源适配器（顶点小说网，UTF-8）
│       ├── blqvdu.py       # 第三源适配器（顶点小说网镜像，gb18030）
│       ├── biquge365.py    # 备源适配器（新笔趣阁，UTF-8）
│       └── __init__.py     # 源注册表 + 健康度感知路由（ordered_sources / first_healthy）
├── scripts/smoke_test.py   # 表驱动端到端断言（遍历 源 × 能力矩阵）
├── run.py                  # uvicorn 入口（4321）
├── requirements.txt
└── README.md
```

**新增一个站点**：在 `app/sources/` 下继承 `Source`、设好 `id/name/base_url/encoding/priority/capabilities`
并实现 9 个解析方法，再在 `__init__.py` 的 `_ALL` 追加实例即可——抓取、重试、缓存、健康度路由、
故障转移、OpenAPI 文档全部自动复用。若源站不支持某能力，把 `capabilities` 对应项置 `False`，
路由层会自动跳过它并在显式指定时返回 400（blqvdu/biquge365 的 `search=False` 就是这么处理的）。

---

## 六、实测结果

`scripts/smoke_test.py` **50/50 全部通过**（表驱动遍历「3 源 × 能力矩阵」+ 路由/错误/缓存）。

### 6.1 服务层

| 项 | 结果 |
|---|---|
| `/api/ping` | 200，2ms |
| `/` 端点索引 | 14 端点 |
| `/api/sources` | 3 源：bqg99, blqvdu, biquge365 |
| `/api/sources/health` | 三源全 OK（5012ms，含真实回源） |

### 6.2 三源逐一验证

| 能力 | bqg99（主源） | blqvdu（镜像） | biquge365（备源） |
|---|---|---|---|
| 首页 | 热门4 推荐9 更新**100** 块6 | 热门4 推荐9 更新30 | 热门4 更新25 块5 |
| 分类字典 | 7 类 | 9 类 | 8 类（paginated=true） |
| 分类列表 | 玄幻 30 本《寰宇之证》 | 玄幻魔法 60 本《拔剑百年，下山即无敌》 | 玄幻魔法 24 本 |
| **分类翻页** | 源站不支持 | 源站不支持 | ✅ **第 1/1769 页**，page=2 内容不同 |
| 排行榜 | **8 榜**（总榜+7分类）各 15 条 | **8 榜**（源站 10 榜，含总榜） | 8 榜（各分类收藏排行聚合） |
| 全本 | 30 本 | 60 本 | 100 本 |
| 搜索 | ✅ `kw=牧神记`→1 条《牧神记》/宅猪 | 400 正确拒绝 | 400 正确拒绝 |
| 书籍详情 | 《牧神记》宅猪·连载·**3360271字**·1920章 | 《百炼飞升录》**虚眞**·连载·**8684章** | 《临渊行》宅猪·连载中·986章 |
| **目录全量** | **1920/1920** 章，首《第一章 天黑别出门》→末《牧神记新番外来啦！》 | **8684/8684** 章，首《第零章 弥罗界之变》→末《煅玉堂 第一百九十九章》 | **986/986** 章 |
| 目录分页 | offset=500&limit=2 → index=[501,502] | 同 | 同 |
| **章节正文** | 3622 字，`next=637338511`，脏标记=无 | 3205 字，`next=88724291`，脏标记=无 | 3341 字，`next=283331`，脏标记=无 |
| **连读回环** | 第2章 3216 字，`prev==第1章cid` ✅ | 第一章 2317 字，`prev` 回环 ✅ | 第二章 2786 字，`prev` 回环 ✅ |
| 广告清洗开关 | 3672 → 3622 字 | 4979 → **3205** 字（清掉 1774 字广告/实体残留） | 3732 → 3341 字 |

> 「脏标记」= 正文中残留 `<p` 或 `script` 的比例，三源均为 0。
> blqvdu 的清洗收益最大（4979→3205），因为源站正文混入了大量转义实体与 `app2()/read2()` 脚本广告。

### 6.3 路由 / 错误 / 缓存

| 场景 | 结果 |
|---|---|
| auto 故障转移 `/api/search` | 自动落到**唯一支持搜索的 bqg99**，4ms（健康度缓存命中） |
| auto 优先级 `/api/home` | 落到最高优先级 bqg99，33ms |
| auto 默认源 `/api/book/2639610` | 源=bqg99《牧神记》，77ms |
| 未知分类 / 不存在书籍 / 未知源 | 均正确 **404** |
| 空搜索词 | **422**（FastAPI 参数校验） |
| 缓存 | 31 条目、命中率 18%、健康度覆盖 3 源 |
| 缓存命中 | 二次 `/api/home` **57ms**（首次 316ms） |
| 服务端日志 | 全链路 200，**零重试触发** |

### 6.4 真实业务链路验证

```
搜索「凡人修仙传」→《凡人修仙传》忘语 source=bqg99 book_id=1012236742
详情          → 完结 · 7447515 字 · 2562 章 · 最新《忘语新书《玄界之门》》
目录前 3 章    → 第一章 山边小村 / 第二章 青牛镇 / 第三章 七玄门
正文首章      → 2419 字，next=1007712797
                「二愣子睁大着双眼，直直望着茅草和烂泥糊成的黑屋顶…」
```

---

## 七、已知限制

### 源站能力差异

| # | 限制 | 影响 | 应对 |
|---|---|---|---|
| 1 | **只有 bqg99 支持站内搜索**，blqvdu / biquge365 源站无搜索端点（404） | 主源宕机时搜索能力整体不可用 | `source=auto` 会自动落到 bqg99；显式指定无搜索的源返回 **400** 并提示改用哪个源 |
| 2 | **bqg99 / blqvdu 的分类页与全本页源站不提供翻页**（bqg99 每类固定 30 条，blqvdu 60 条） | 无法深度浏览这两个源的分类 | 用 `source=biquge365`（玄幻魔法 **1769 页**，`total_pages`/`has_more` 已解析） |
| 3 | **bqg99 搜索对短词命中差**：实测 `斗罗`→0 条、`斗罗大`→0 条、`斗罗大陆`→1 条（已回源核对，属上游匹配策略） | 关键词太短会搜不到 | 传较完整书名或作者名（`天蚕土豆`→8 条） |
| 4 | **搜索结果含镜像重复条目**，已按 (书名, 作者) 去重 | `万相之王` 由源站 3 条收敛为 1 条 | 属预期行为；如需全部镜像条目可去掉 `bqg99.py` 中的去重 |
| 5 | blqvdu / biquge365 源站未提供字数 | `word_count` 为 `null` | 已用 `og:novel:*` 与页面 span 兜底，缺失即返回 null 而非 0 |

### id 与编码

| # | 限制 | 说明 |
|---|---|---|
| 6 | **`book_id` / `chapter_id` 跨源不通用** | bqg99 是 `2639610`、blqvdu 是 `4_4903`（分类id_书籍id）、biquge365 是 `78201`。必须沿用列表/搜索返回的同一 `source` |
| 7 | **id 一律是字符串** | biquge365 章节 id 含前导零（`02622105`），转成 int 会丢信息并导致 404 |
| 8 | **blqvdu 是 gb18030 编码** | 源站 header 声明 gb2312、meta 声明 gbk。若改成 utf-8 会全站乱码，且「下一章」文字匹配失败导致导航丢失 |

### 运行与网络

| # | 限制 | 说明 |
|---|---|---|
| 9 | **上游会整站临时不可达** | 实测 bqg99 曾直连与代理双双失败数小时后自愈；biquge365 在另一时段**直连必失败、仅代理可通**。已用健康度路由 + 4 次退避重试吸收 |
| 10 | **本机存在系统代理**（127.0.0.1:10808） | 抓取层默认 `trust_env=True` 自动沿用；`XS_NO_PROXY=1` 强制直连，`XS_PROXY=` 显式指定。注意：调用本服务自身时若用 httpx，需 `trust_env=False`，否则代理会劫持 127.0.0.1 请求并返回 503 |
| 11 | 单次慢响应可达 6s+ | 实测 bqg99 `/rank.html` 出现过 6.8s；接口无响应时间 SLA |
| 12 | **缓存有滞后** | 目录/详情 6h、正文 24h，新章节不会实时出现。需最新数据请 `DELETE /api/cache` |
| 13 | 缓存与健康度均为**进程内内存态** | 重启即清空；单进程部署足够，多 worker 需换 Redis 等共享存储 |
| 14 | 已排除的站点不保证永久排除 | bqg788/bqgam 正文靠 JS 异步加载（返回「加载中……」），若将来改成服务端渲染可再接入；xshuquge（书趣阁）内容最新但 Python httpx 下 HTTPS 全超时，同样待观察 |

---

## 八、免责声明

本项目仅用于**个人学习与技术研究**（HTML 结构解析、FastAPI 文档工程、爬虫容错设计）。所有小说内容版权归原作者及原网站所有，请勿用于任何商业用途或大规模内容抓取分发。请求已做并发限制（≤8）与缓存降压，请自觉控制调用频率。
