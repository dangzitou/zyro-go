# zyro-go

当前目录名为 `zyro-go`，但项目实际是一个基于 Spring Boot 的点评类后端服务，`pom.xml` 中的制品名为 `hm-dianping`。

项目围绕本地生活场景实现了商铺查询、博客探店、关注、优惠券秒杀、登录鉴权等能力，并重点演示 Redis 在缓存、分布式锁、Geo、Stream 和全局 ID 生成中的使用方式。

## 技术栈

- Java 8
- Spring Boot 2.3.12.RELEASE
- MyBatis-Plus 3.4.3
- MySQL 8
- Redis
- Redisson
- Hutool

## 功能概览

- 手机验证码登录与用户态维护
- 商铺缓存查询与缓存更新
- 基于 Redis Geo 的附近商铺查询
- 探店博客发布、点赞、关注
- 优惠券秒杀
- 基于 Lua + Redis Stream 的异步下单
- 基于 Redisson 的分布式锁控制一人一单
- 基于 Redis 的全局唯一 ID 生成

## 目录说明

```text
.
├── src/main/java/com/hmdp
│   ├── config
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── interceptor
│   ├── mapper
│   ├── service
│   └── utils
├── src/main/resources
│   ├── application.yaml
│   ├── db/hmdp.sql
│   ├── lua
│   └── mapper
└── test
```

## 环境要求

- JDK 8
- Maven 3.6+
- MySQL 8.x
- Redis 6.x 或更高版本

## 配置说明

默认配置文件：`src/main/resources/application.yaml`

当前仓库中的默认值为：

- 服务端口：`8081`
- MySQL：`jdbc:mysql://127.0.0.1:3306/hmdp`
- MySQL 用户名：`root`
- MySQL 密码：`1234`
- Redis Host：`172.28.130.63`
- Redis 端口：`6379`
- Redis 密码：`123456`

启动前建议先按你的本地环境修改数据库和 Redis 配置。

## 初始化数据

项目自带 SQL 文件：

- `src/main/resources/db/hmdp.sql`

使用方式：

1. 在 MySQL 中创建数据库 `hmdp`
2. 执行 `hmdp.sql` 导入表结构和示例数据

## 启动方式

在项目根目录执行：

```bash
mvn spring-boot:run
```

或者先打包再启动：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

启动入口类：

- `com.hmdp.HmDianPingApplication`

## 关键实现点

- 商铺查询在 `ShopServiceImpl` 中实现了缓存穿透、缓存击穿的处理方式
- 秒杀下单在 `VoucherOrderServiceImpl` 中结合 Lua、Redis Stream 和 Redisson 完成异步化与并发控制
- Redis 脚本位于 `src/main/resources/lua`
- 通用 Redis 工具类位于 `src/main/java/com/hmdp/utils`

## 测试

可执行：

```bash
mvn test
```

仓库中还包含一个 JMeter 文件：

- `test/HTTP Request.jmx`

可用于接口压测或联调。
