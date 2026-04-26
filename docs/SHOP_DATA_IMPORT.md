# Shop Data Import

## 概述

项目已补充一套可复用的真实门店导入方案，用于把北京、广州、厦门的公开 POI 数据批量导入 `tb_shop`，并同步重建 Redis GEO 索引。

当前方案优先保证三件事：

1. 数据来源真实且可追溯。
2. 不为了一次性导入而破坏现有业务表结构。
3. 可以重复执行，后续继续扩城市或补数据时不需要重写一遍。

## 数据来源

- 主数据源：OpenStreetMap / Overpass API
- 许可：ODbL
- 当前导入城市：
  - `beijing`
  - `guangzhou`
  - `xiamen`

说明：

- 门店名称、经纬度、部分地址、部分营业时间来自公开地图 POI。
- `tb_shop` 里业务必须但 OSM 常缺失的字段，如 `images`、`avg_price`、`sold`、`comments`、`score`，采用确定性补齐策略生成，保证页面和接口可直接使用。
- 这类补齐字段适合演示、检索、推荐、Agent 试跑，不应对外宣称为官方经营数据。

## 脚本位置

- 导入脚本：[scripts/import_osm_shops.py](/E:/Develop/projects/back-end/zyro-go/scripts/import_osm_shops.py)

## 已落地能力

### 1. 批量抓取

脚本按城市 bbox 分片请求 Overpass，避免一次请求过大。

抓取范围包含：

- `amenity=restaurant|fast_food|cafe|bar|pub|nightclub|karaoke_box|spa`
- `shop=hairdresser|beauty|cosmetics|massage|nail_salon`
- `leisure=fitness_centre|sports_centre|playground|amusement_arcade`

### 2. 分类映射

外部 POI 标签映射到项目内 `tb_shop_type`：

- `1 美食`
- `2 KTV`
- `3 丽人·美发`
- `4 健身运动`
- `5 按摩·足疗`
- `6 美容SPA`
- `7 亲子游乐`
- `8 酒吧`
- `9 轰趴馆`
- `10 美睫·美甲`

说明：

- `9 轰趴馆` 在 OSM 公开数据中非常少，本轮基本没有规模化覆盖。
- `10 美睫·美甲` 主要通过 `beauty=nails`、`shop=nail_salon` 和名称关键词识别。

### 3. 幂等导入

脚本会维护来源映射表：

- `tb_shop_import_source`

表内保存：

- `source_id`
- `shop_id`
- `city`

作用：

- 同一个 OSM 对象重复执行导入时，不会再次插入 `tb_shop`
- 后续可以继续补城市、补分类、补 GEO，而不需要手工去重

### 4. Redis GEO 重建

脚本支持导入后重建：

- `shop:geo:1`
- `shop:geo:2`
- `shop:geo:3`
- ...

这一步很重要，因为前端和推荐逻辑里有“按坐标查附近门店”的能力，只导入 MySQL 不回填 Redis GEO 会导致附近查询失真。

## 运行方式

### 导入并同步 GEO

```bash
python scripts/import_osm_shops.py \
  --cities beijing,guangzhou,xiamen \
  --mysql-bin E:\Develop\mysql-9.4.0-winx64\bin\mysql.exe \
  --redis-cli E:\Develop\Redis-x64-5.0.14.1\redis-cli.exe \
  --redis-password 123456 \
  --sync-redis-geo \
  --export-json data/import/osm-shops-final.json
```

### 仅导出并校验，不新增数据

说明：

- 如果 `tb_shop_import_source` 已经记录过对应 `source_id`，再次运行会显示抓取候选数，但 `Inserted` 为 `0`，这是预期行为。

```bash
python scripts/import_osm_shops.py \
  --cities xiamen \
  --mysql-bin E:\Develop\mysql-9.4.0-winx64\bin\mysql.exe \
  --export-json data/import/osm-shops-xiamen-rerun.json
```

## 当前本地导入结果

导入时间：`2026-04-26`

### MySQL

- `tb_shop` 总量：`8127`

按来源城市统计：

- `beijing`: `5358`
- `guangzhou`: `2304`
- `xiamen`: `452`

按项目分类统计：

- `1 美食`: `7141`
- `2 KTV`: `21`
- `3 丽人·美发`: `164`
- `4 健身运动`: `385`
- `5 按摩·足疗`: `25`
- `6 美容SPA`: `71`
- `7 亲子游乐`: `72`
- `8 酒吧`: `239`
- `10 美睫·美甲`: `9`

### Redis GEO

已重建以下索引，并与 `tb_shop` 当前分类数量对齐：

- `shop:geo:1 = 7141`
- `shop:geo:2 = 21`
- `shop:geo:3 = 164`
- `shop:geo:4 = 385`
- `shop:geo:5 = 25`
- `shop:geo:6 = 71`
- `shop:geo:7 = 72`
- `shop:geo:8 = 239`
- `shop:geo:10 = 9`

## 产物位置

当前本地生成的数据文件：

- [data/import/osm-shops-final.json](/E:/Develop/projects/back-end/zyro-go/data/import/osm-shops-final.json)

这些文件用于：

- 导入过程审计
- 来源映射回填
- 后续抽样验收

## 注意事项

### 1. 许可与署名

使用 OSM 数据时，需要遵守 ODbL 及相应署名要求。

建议在项目对外文档中明确说明：

- 基础 POI 来源于 OpenStreetMap contributors

### 2. 数据质量边界

OSM 的优势是公开、可批量、许可清晰；不足是：

- 中国部分长尾门店覆盖度不如商业地图
- 地址完整度不稳定
- 部分类目非常稀疏

如果后续要做企业级生产数据底座，建议：

1. OSM 做基础门店库
2. 核心城市和高价值类目叠加人工校验
3. 如需更高覆盖率，再引入商业授权数据源

### 3. 字段解释

以下字段属于“项目兼容补齐”，不是 OSM 原始经营数据：

- `images`
- `avg_price`
- `sold`
- `comments`
- `score`

这些字段适合：

- 列表展示
- 排序演示
- 推荐链路联调
- Agent 实战测试

不适合：

- 财务口径
- 商家对账
- 对外运营报表

## 后续建议

如果要继续把这套门店底座做强，建议按这个顺序推进：

1. 增加更多城市：深圳、上海、杭州、成都
2. 增加抽样质检脚本：随机抽店名、坐标、地址完整度
3. 为高价值门店增加人工校验标签
4. 为 RAG 侧补充“门店别名、商圈别名、品牌别名”知识
5. 在后台增加受保护的导入管理入口，而不是长期只靠脚本
