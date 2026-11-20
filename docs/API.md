# 服务端接口说明

客户端本身不存储任何数据，以下接口均由用户自行配置的服务端提供，与 LibreTV / MoonTV 的
服务端接口保持一致。所有请求都需要携带登录后服务端下发的 `auth` Cookie。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/login` | 登录，Body `{"username":"...","password":"..."}`，响应头下发 `Set-Cookie: auth=...` |
| GET | `/api/search?q=关键词` | 搜索影视，返回 `{"results":[...]}` |
| GET | `/api/detail?source=源&id=ID` | 影视详情（含 `episodes` 播放地址数组） |
| GET | `/api/douban?type=&tag=&pageSize=&pageStart=` | 豆瓣分类列表 |
| GET | `/api/douban/recommends?kind=&category=&format=&region=&year=&platform=&sort=&label=&limit=&start=` | 分类 + 筛选推荐 |
| GET | `/api/bangumi/calendar` | 每日放送（按星期分组） |
| GET | `/api/playrecords` | 拉取播放记录 |
| POST | `/api/playrecords` | 保存播放记录，Body `{"key":"源+ID","record":{...}}` |
| DELETE | `/api/playrecords` | 清空播放记录 |
| GET | `/api/skipconfigs` | 拉取跳过片头片尾配置 |
| POST | `/api/skipconfigs` | 保存跳过配置，Body `{"key":"源+ID","config":{...}}` |
| GET | `/api/favorites` | 拉取收藏 |
| POST | `/api/favorites` | 新增收藏，Body `{"key":"源+ID","favorite":{...}}` |
| DELETE | `/api/favorites?key=源+ID` | 取消收藏 |

## 影视对象字段

客户端读取的影视对象字段（缺省均可省略）：

| 字段 | 说明 |
| --- | --- |
| `id` | 该源内的影视 ID |
| `title` | 片名 |
| `poster` | 海报地址 |
| `year` | 年份 |
| `source` | 源标识 |
| `source_name` | 源名称（详情页展示） |
| `type_name` | 类型（电影 / 剧集等） |
| `desc` | 简介 |
| `rate` | 评分 |
| `douban_id` | 豆瓣 ID |
| `episodes` | 播放地址数组（m3u8 等） |
| `episodes_titles` | 分集标题数组（可选） |

收藏与播放记录以 `source + "+" + id` 作为唯一 key，因此服务端需要保证同一部片在不同源下
的记录可以分别存储；客户端在展示收藏时会按「片名 + 年份」做归一化匹配，避免换源后重复。
