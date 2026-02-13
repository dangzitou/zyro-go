# zyro-go

A Spring Boot + Redis based local lifestyle service platform, similar to Yelp/Dianping, implementing core features such as shop queries, flash sales vouchers, user check-ins, friend following, and blog likes.

## 📋 Project Overview

zyro-go is a complete backend system for a local lifestyle service platform with the following main features:

- 🏪 **Shop Management**: Shop information queries, category browsing, location-based nearby shop recommendations
- 👤 **User System**: Phone number login, user profile management, follow/unfollow functionality
- 🎫 **Voucher System**: Regular and flash sale vouchers, high-concurrency seckill ordering
- 📝 **Blog/Review System**: Publish reviews, likes, comments, followed user feeds
- ✅ **Check-in System**: Bitmap-based consecutive check-in tracking
- 📍 **Geolocation**: Redis GEO-based nearby shop queries (within 5km radius)

## 🛠️ Tech Stack

### Backend Framework
- **Spring Boot 2.3.12**: Core framework
- **MyBatis-Plus 3.4.3**: Persistence layer framework for simplified CRUD
- **Spring Data Redis**: Redis integration
- **Redisson 3.13.6**: Distributed lock implementation

### Databases
- **MySQL 8.0.30**: Relational database for core business data
- **Redis 6.x**: Cache, distributed locks, message queue

### Utility Libraries
- **Hutool 5.7.17**: Java utility library
- **Lombok 1.18.34**: Simplify entity class code
- **Lettuce 6.1.6**: Redis client (async, non-blocking)

## 🏗️ Core Features

### 1. Caching Strategies

#### Cache Penetration Solution
- Cache null values to prevent excessive database queries
- Short expiration time (2 minutes)

#### Cache Breakdown Solutions
Two approaches provided:
- **Mutex Lock**: Use Redis SETNX for distributed locks, block other requests during cache rebuild
- **Logical Expiration**: No TTL, use logical expiration time, async cache updates

```java
// CacheClient utility class provides unified cache operations
- queryWithPassThrough()   // Solve cache penetration
- queryWithMutex()         // Mutex lock approach
- queryWithLogicalExpire() // Logical expiration approach
```

### 2. Flash Sale (Seckill) System

#### Architecture Design
```
User Request
  ↓
Lua Script (Redis) - Atomic stock check & user validation
  ├─ Return 1: Insufficient stock
  ├─ Return 2: User already ordered
  └─ Return 0: Success → Send to Stream queue
  ↓
Redis Stream Consumer (async order processing)
  ↓
Redisson Distributed Lock (per user) → DB insert → Decrement stock
```

#### Key Technical Points
- **Lua Script** (`seckill.lua`): Ensures atomicity of stock check, duplicate user check, and message sending
- **Redis Stream**: Reliable message queue with consumer groups and acknowledgment
- **Redisson Distributed Lock**: Prevents duplicate orders from the same user
- **Optimistic Locking**: Stock decrement with `stock > 0` condition

### 3. Distributed Locks

#### Custom Redis Lock
```java
SimpleRedisLock: 
- Uses SET NX EX command for locking
- Lua script ensures atomic unlock
- Thread ID prevents deleting other threads' locks
```

#### Redisson Distributed Lock
```java
RLock lock = redissonClient.getLock("lock:order:" + userId);
lock.lock();
try {
    // Business logic
} finally {
    lock.unlock();
}
```

**Features**:
- Reentrant lock
- Watchdog automatic renewal
- Master-slave consistency

### 4. User Login & Session Management

- **SMS Verification Code Login**: Redis stores verification codes with 5-minute expiration
- **Token Session Management**: Redis Hash stores user information
- **Auto-renewal**: Each request refreshes token expiration (30 minutes)
- **Interceptor Chain**:
  - `RefreshTokenInterceptor`: Refreshes token expiration time
  - `LoginInterceptor`: Validates login status

### 5. Friend Following & Common Follows

- **Redis Set**: Stores user following lists
- **Set Intersection**: Quick common follows query
- **Feed Stream Push**: Followed users' blogs pushed to Redis ZSet (sorted by timestamp)

### 6. Check-in Feature

- **Redis Bitmap**: One bitmap per user per month (31 bits)
- **Consecutive Check-in Stats**: Bit operations for consecutive check-in counting
- **Space Optimization**: One month's check-in data requires only 4 bytes

### 7. Geolocation Service

- **Redis GEO**: Stores shop coordinates (latitude/longitude)
- **GEORADIUS**: Queries shops within 5km radius
- **Distance Results**: Returns results sorted by distance

### 8. Global Unique ID Generator

```java
RedisIdWorker:
- Timestamp (31 bits) + Sequence number (32 bits)
- Uses Redis INCRBY for sequence generation
- Supports high-concurrency scenarios
```

## 📊 Database Design

Main Tables:
- `tb_user`: User information
- `tb_shop`: Shop information
- `tb_shop_type`: Shop categories
- `tb_voucher`: Voucher information
- `tb_seckill_voucher`: Flash sale vouchers
- `tb_voucher_order`: Voucher orders
- `tb_blog`: Review/blog posts
- `tb_blog_comments`: Blog comments
- `tb_follow`: User follow relationships
- `tb_user_info`: Detailed user information

Database initialization script: `src/main/resources/db/hmdp.sql`

## 🚀 Quick Start

### Requirements
- JDK 1.8+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+

### Configuration

Modify `src/main/resources/application.yaml`:

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

### Setup Steps

1. **Create Database**
```bash
mysql -u root -p
CREATE DATABASE hmdp;
USE hmdp;
SOURCE src/main/resources/db/hmdp.sql;
```

2. **Start Redis**
```bash
redis-server
```

3. **Build and Run**
```bash
mvn clean install
mvn spring-boot:run
```

4. **Access APIs**
- Default port: 8081
- Swagger docs (if configured): http://localhost:8081/swagger-ui.html

## 📁 Project Structure

```
src/main/java/com/hmdp/
├── config/              # Configuration classes (MVC, Redis, MyBatis-Plus)
├── controller/          # Controller layer
│   ├── BlogController.java
│   ├── ShopController.java
│   ├── UserController.java
│   ├── VoucherController.java
│   └── ...
├── dto/                 # Data Transfer Objects
├── entity/              # Entity classes
├── interceptor/         # Interceptors (login, token refresh)
├── mapper/              # MyBatis Mapper interfaces
├── service/             # Business logic layer
│   └── impl/
└── utils/               # Utility classes
    ├── CacheClient.java        # Cache utilities
    ├── RedisIdWorker.java      # ID generator
    ├── SimpleRedisLock.java    # Simple distributed lock
    └── ...

src/main/resources/
├── application.yaml     # Configuration file
├── db/                  # Database scripts
│   └── hmdp.sql
├── lua/                 # Lua scripts
│   ├── seckill.lua      # Seckill script
│   └── unlock.lua       # Unlock script
└── mapper/              # MyBatis XML files
```

## 🔧 Main APIs

### User Related
- `POST /user/code` - Send verification code
- `POST /user/login` - User login
- `GET /user/me` - Get current user info
- `POST /user/sign` - Daily check-in

### Shop Related
- `GET /shop/{id}` - Query shop details
- `GET /shop/of/type` - Query shops by type with pagination
- `PUT /shop` - Update shop information

### Voucher Related
- `GET /voucher/list/{shopId}` - Query shop voucher list
- `POST /voucher-order/seckill/{id}` - Flash sale order

### Blog Related
- `POST /blog` - Publish blog
- `GET /blog/{id}` - Query blog details
- `PUT /blog/like/{id}` - Like/unlike
- `GET /blog/of/follow` - Query followed users' blogs

### Follow Related
- `PUT /follow/{id}/{isFollow}` - Follow/unfollow user
- `GET /follow/or/not/{id}` - Check if following
- `GET /follow/common/{id}` - Query common follows

## 🎯 Technical Highlights

1. **Multi-level Cache Architecture**: Hot data caching + solutions for cache penetration/breakdown/avalanche
2. **High-Concurrency Seckill**: Lua + Redis Stream + distributed locks + optimistic locking combination
3. **Distributed Locks**: Custom simple lock + Redisson production-grade lock comparison
4. **Message Queue**: Redis Stream for reliable message queuing with ACK and Pending mechanisms
5. **Session Management**: Stateless Token + Redis for distributed sessions
6. **Feed Stream**: Redis ZSet-based push model feed stream
7. **GEO Location**: Redis GEO for nearby shop queries
8. **Bitmap Application**: Efficient storage for user check-in data

## 📝 Learning Recommendations

This project is suitable for learning:
1. Spring Boot rapid development
2. Practical use cases of Redis data structures
3. Cache design in distributed systems
4. Flash sale system design for high concurrency
5. Distributed lock principles and usage
6. Message queue applications in order systems

## 📄 License

This project is for learning and educational purposes only.

## 🙏 Acknowledgments

This project is developed based on learning resources and project examples from Heima Programmer.

---

**Note**: This is a learning/demo project. Production use requires further optimization and security hardening.
