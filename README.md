# zyro-go

一个基于 Spring Boot + Redis 的本地生活服务平台，类似于大众点评，实现了店铺查询、优惠券秒杀、用户签到、好友关注、博客点赞等核心功能。

## 📋 项目简介

zyro-go 是一个完整的本地生活服务平台后端系统，主要功能包括：

- 🏪 **商铺管理**：商铺信息查询、分类浏览、基于地理位置的附近商铺推荐
- 👤 **用户系统**：手机号登录、用户信息管理、关注/取关功能
- 🎫 **优惠券系统**：普通优惠券和秒杀券发放、高并发秒杀下单
- 📝 **探店笔记**：发布笔记、点赞、评论、关注用户动态
- ✅ **签到功能**：基于 Bitmap 的连续签到统计
- 📍 **地理位置**：基于 Redis GEO 的附近商铺查询（5km 范围内）

## 🛠️ 技术栈

### 后端框架
- **Spring Boot 2.3.12**：核心框架
- **MyBatis-Plus 3.4.3**：持久层框架，简化 CRUD 操作
- **Spring Data Redis**：Redis 集成
- **Redisson 3.13.6**：分布式锁实现

### 数据库
- **MySQL 8.0.30**：关系型数据库，存储核心业务数据
- **Redis 6.x**：缓存、分布式锁、消息队列

### 工具库
- **Hutool 5.7.17**：Java 工具库
- **Lombok 1.18.34**：简化实体类编写
- **Lettuce 6.1.6**：Redis 客户端（异步、非阻塞）

## 🏗️ 核心功能实现

### 1. 缓存策略

#### 缓存穿透解决方案
- 使用空值缓存，防止大量请求直接打到数据库
- 设置较短的过期时间（2分钟）

#### 缓存击穿解决方案
提供两种方案：
- **互斥锁方案**：使用 Redis SETNX 实现分布式锁，重建缓存时阻塞其他请求
- **逻辑过期方案**：不设置 TTL，使用逻辑过期时间，异步更新缓存

```java
// CacheClient 工具类提供统一的缓存操作
- queryWithPassThrough()  // 解决缓存穿透
- queryWithMutex()        // 互斥锁方案
- queryWithLogicalExpire() // 逻辑过期方案
```

### 2. 秒杀系统

#### 架构设计
```
用户请求
  ↓
Lua 脚本（Redis）- 原子性检查库存和用户资格
  ├─ 返回 1：库存不足
  ├─ 返回 2：用户已下单
  └─ 返回 0：成功 → 发送到 Stream 队列
  ↓
Redis Stream 消费者（异步处理订单）
  ↓
Redisson 分布式锁（按用户加锁）→ 数据库插入 → 扣减库存
```

#### 关键技术点
- **Lua 脚本**（`seckill.lua`）：保证库存检查、用户判重、消息发送的原子性
- **Redis Stream**：实现可靠的消息队列，支持消费者组和消息确认
- **Redisson 分布式锁**：防止同一用户重复下单
- **乐观锁**：扣减库存时使用 `stock > 0` 条件判断

### 3. 分布式锁

#### 自定义 Redis 锁
```java
SimpleRedisLock: 
- 使用 SET NX EX 命令实现加锁
- Lua 脚本保证解锁的原子性
- 线程标识防止误删其他线程的锁
```

#### Redisson 分布式锁
```java
RLock lock = redissonClient.getLock("lock:order:" + userId);
lock.lock();
try {
    // 业务逻辑
} finally {
    lock.unlock();
}
```

**特点**：
- 可重入锁
- 看门狗自动续期
- 主从一致性保障

### 4. 用户登录与会话管理

- **手机验证码登录**：Redis 存储验证码，5分钟过期
- **Token 会话管理**：基于 Redis Hash 存储用户信息
- **自动续期**：每次请求刷新 Token 有效期（30分钟）
- **拦截器链**：
  - `RefreshTokenInterceptor`：刷新 Token 过期时间
  - `LoginInterceptor`：校验登录状态

### 5. 好友关注与共同关注

- **Redis Set**：存储用户的关注列表
- **Set 交集**：快速查询共同关注
- **Feed 流推送**：关注用户的笔记推送到 Redis ZSet（按时间戳排序）

### 6. 签到功能

- **Redis Bitmap**：每个用户每月一个 Bitmap（31位）
- **连续签到统计**：使用位运算统计连续签到天数
- **空间优化**：1个月签到数据仅需 4 字节

### 7. 地理位置服务

- **Redis GEO**：存储商铺的经纬度坐标
- **GEORADIUS**：查询 5km 范围内的商铺
- **返回距离**：按距离排序返回结果

### 8. 全局唯一 ID 生成器

```java
RedisIdWorker:
- 时间戳（31位）+ 序列号（32位）
- 使用 Redis INCRBY 生成序列号
- 支持高并发场景
```

## 📊 数据库设计

主要数据表：
- `tb_user`：用户信息表
- `tb_shop`：商铺信息表
- `tb_shop_type`：商铺类型表
- `tb_voucher`：优惠券表
- `tb_seckill_voucher`：秒杀券表
- `tb_voucher_order`：优惠券订单表
- `tb_blog`：探店笔记表
- `tb_blog_comments`：笔记评论表
- `tb_follow`：用户关注表
- `tb_user_info`：用户详细信息表

数据库初始化脚本：`src/main/resources/db/hmdp.sql`

## 🚀 快速开始

### 环境要求
- JDK 1.8+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+

### 配置文件

修改 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379
    password: your_redis_password
```

### 启动步骤

1. **创建数据库**
```bash
mysql -u root -p
CREATE DATABASE hmdp;
USE hmdp;
SOURCE src/main/resources/db/hmdp.sql;
```

2. **启动 Redis**
```bash
redis-server
```

3. **编译运行**
```bash
mvn clean install
mvn spring-boot:run
```

4. **访问接口**
- 默认端口：8081
- Swagger 文档（如已配置）：http://localhost:8081/swagger-ui.html

## 📁 项目结构

```
src/main/java/com/hmdp/
├── config/              # 配置类（MVC、Redis、MyBatis-Plus）
├── controller/          # 控制器层
│   ├── BlogController.java
│   ├── ShopController.java
│   ├── UserController.java
│   ├── VoucherController.java
│   └── ...
├── dto/                 # 数据传输对象
├── entity/              # 实体类
├── interceptor/         # 拦截器（登录、Token 刷新）
├── mapper/              # MyBatis Mapper 接口
├── service/             # 业务逻辑层
│   └── impl/
└── utils/               # 工具类
    ├── CacheClient.java        # 缓存工具
    ├── RedisIdWorker.java      # ID 生成器
    ├── SimpleRedisLock.java    # 简单分布式锁
    └── ...

src/main/resources/
├── application.yaml     # 配置文件
├── db/                  # 数据库脚本
│   └── hmdp.sql
├── lua/                 # Lua 脚本
│   ├── seckill.lua      # 秒杀脚本
│   └── unlock.lua       # 解锁脚本
└── mapper/              # MyBatis XML 文件
```

## 🔧 主要接口

### 用户相关
- `POST /user/code` - 发送验证码
- `POST /user/login` - 登录
- `GET /user/me` - 获取当前用户信息
- `POST /user/sign` - 签到

### 商铺相关
- `GET /shop/{id}` - 查询商铺详情
- `GET /shop/of/type` - 按类型分页查询商铺
- `PUT /shop` - 更新商铺信息

### 优惠券相关
- `GET /voucher/list/{shopId}` - 查询店铺的优惠券列表
- `POST /voucher-order/seckill/{id}` - 秒杀下单

### 笔记相关
- `POST /blog` - 发布笔记
- `GET /blog/{id}` - 查询笔记详情
- `PUT /blog/like/{id}` - 点赞/取消点赞
- `GET /blog/of/follow` - 查询关注用户的笔记

### 关注相关
- `PUT /follow/{id}/{isFollow}` - 关注/取关用户
- `GET /follow/or/not/{id}` - 查询是否关注
- `GET /follow/common/{id}` - 查询共同关注

## 🎯 技术亮点

1. **多级缓存架构**：热点数据缓存 + 缓存穿透/击穿/雪崩解决方案
2. **高并发秒杀**：Lua + Redis Stream + 分布式锁 + 乐观锁组合方案
3. **分布式锁**：自研简单锁 + Redisson 生产级锁对比实现
4. **消息队列**：Redis Stream 实现可靠消息队列，支持 ACK 和 Pending 机制
5. **会话管理**：无状态 Token + Redis 实现分布式会话
6. **Feed 流**：基于 Redis ZSet 实现推送模式的 Feed 流
7. **GEO 地理位置**：Redis GEO 实现附近商铺查询
8. **Bitmap 应用**：用户签到数据高效存储

## 📝 学习建议

这个项目适合学习以下知识点：
1. Spring Boot 快速开发
2. Redis 常用数据结构的实际应用场景
3. 分布式系统中的缓存设计
4. 高并发场景下的秒杀系统设计
5. 分布式锁的实现原理和使用
6. 消息队列在订单系统中的应用

## 📄 许可证

本项目仅供学习交流使用。

## 🙏 致谢

本项目基于黑马程序员的学习资源和项目案例进行开发。

---

**注意**：本项目为学习示例项目，生产环境使用需要进一步优化和安全加固。
