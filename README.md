# 🔐 ZKP Auth System | 零知识证明身份验证系统

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.10-green.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Protocol](https://img.shields.io/badge/Cryptography-Schnorr%20ZKP-purple.svg)](https://en.wikipedia.org/wiki/Schnorr_signature)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **零知识证明（Zero-Knowledge Proof）** 登录系统：用户证明拥有私钥而无需传输私钥，服务端仅存储公钥，彻底杜绝拖库风险。

---

## 🎯 核心特性

- **零知识认证**：基于 Schnorr 协议的交互式证明，服务器**永不接触用户私钥**
- **高可用架构**：PostgreSQL 一主二从 + Redis 6节点 Cluster + Kafka 4.0 KRaft 三节点集群
- **响应式设计**：Spring WebFlux 非阻塞 IO，支持高并发登录请求
- **防重放攻击**：Redis 存储一次性挑战（Challenge），5分钟 TTL 自动过期
- **审计追踪**：Kafka 异步记录所有登录事件（成功/失败/注册）

---

## 🏗️ 系统架构

### 认证流程（Schnorr Protocol）

```mermaid
sequenceDiagram
    participant U as 用户(浏览器)
    participant A as API Gateway
    participant S as ZKP Service
    participant R as Redis Cluster
    participant P as PostgreSQL
    
    Note over U,P: 注册阶段
    U->>U: 生成密钥对 (x, Y=g^x)
    U->>A: POST /register {username, Y}
    A->>P: 存储公钥 Y
    P-->>U: 注册成功（仅存储公钥）
    
    Note over U,P: 登录阶段 - 挑战响应
    U->>A: POST /challenge {username}
    A->>S: 生成随机数 r
    S->>S: 计算 R = g^r
    S->>R: 存储 Challenge{username:r:R} TTL=5min
    S-->>U: 返回 {R, p, q, g, challenge_id}
    
    U->>U: 计算 c=H(R||Y||username)<br/>s = r' + c*x mod q
    U->>A: POST /verify {challenge_id, s, clientR}
    A->>S: 验证 g^s == R * Y^c mod p ?
    S->>R: 删除挑战（防重放）
    S->>P: 更新登录时间
    S-->>U: JWT Token
```

### 基础设施拓扑

```mermaid
graph TB
    App[Spring Boot App] --> Pg[(PostgreSQL<br/>Primary:5432)]
    Pg -.->|Streaming| Rep1[(Replica:5433)]
    Pg -.->|Streaming| Rep2[(Replica:5434)]
    
    App --> Redis[Redis Cluster<br/>6 Nodes/3 Masters]
    App --> Kafka[Kafka 4.0 KRaft<br/>3 Brokers]
    
    subgraph "Redis Cluster 172.28.0.x"
        R1[Master 101<br/>Slots 0-5460]
        R2[Master 102<br/>Slots 5461-10922]
        R3[Master 103<br/>Slots 10923-16383]
        R4[Slave 104]
        R5[Slave 105]
        R6[Slave 106]
    end
```

---

## 🛠️ 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| **Spring Boot** | 3.5.10 | 响应式 Web 框架（WebFlux） |
| **Java** | 17 | 运行时 |
| **Bouncy Castle** | 1.83 | 大数运算与密码学原语 |
| **PostgreSQL** | 16 | 主从复制（1主2从）存储用户公钥 |
| **Redis** | 8.4 | Cluster 模式存储挑战值与防重放 |
| **Kafka** | 4.0.0 | KRaft 模式，审计日志 |
| **Protocol** | Schnorr | 零知识证明协议 |

---

## 🚀 快速开始

### 环境要求

- Docker Engine 24.0+
- Docker Compose 2.20+
- Java 17（本地运行时需要）
- Maven 3.9+

### 1. 启动基础设施

```bash
# 1.Springboot标准打包
mvn clean package -DskipTests   
# 2. 配置环境变量
cp .env.example .env
# 编辑 .env
# 3. 构建应用镜像并启动所有服务（首次约3-无限分钟，看你的网络情况hh）
docker compose up --build

# 4. 等待健康检查通过（约30秒）
docker compose logs -f zkp-auth | grep "Started ZkpAuthApplication"
```

### 2. 启动应用

```bash
docker-compose up --build
```

### 3. 验证并登录（Verify）

客户端使用私钥计算 $s = r + c \cdot x \mod q$ 后提交：

```bash
curl -X POST http://localhost:8080/api/v1/auth/verify \
  -d '{
    "challengeId": "<上一步的uuid>",
    "s": "<证明值s，十六进制>",
    "clientR": "<承诺值R，十六进制>"
  }'
```

**成功返回 JWT**：
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "type": "Bearer",
  "expiresIn": 86400
}
```

---

## ⚙️ 容器内网络配置

Spring Boot 应用通过**Docker DNS**访问基础设施（无需 localhost）：

```yaml
# application.yaml（容器内配置）
spring:
  datasource:
    url: jdbc:postgresql://pg-primary:5432/zk_auth  # 容器名而非 localhost
  data:
    redis:
      cluster:
        nodes: 172.28.0.101:6379,172.28.0.102:6379,... # Redis使用静态IP
  kafka:
    bootstrap-servers: kafka-1:19092,kafka-2:29092,kafka-3:39092 # 内部PLAINTEXT端口
```

### 端口暴露说明

- **对外暴露**：`8080`（API）、`5432-5434`（PostgreSQL，开发调试用）、`6379-6384`（Redis）、`9092-9094`（Kafka）
- **仅内部网络**：所有服务通过 Docker Network `redisnet` / `kafkanet` / 默认桥接通信


---

## 📁 项目结构

```
src/main/java/com/tmd/zkp_rkp/
├── config/
│   ├── ZkpCryptoConfig.java          # Schnorr 群参数 (p, q, g) 与 Bean 配置
│   ├── RedisConfig.java              # Reactive Redis Cluster 连接池
│   └── KafkaProducerConfig.java      # Kafka 生产者序列化配置
├── controller/
│   └── AuthController.java           # REST API 端点 (/register, /challenge, /verify)
├── dto/
│   └── AuthDTOs.java                 # Java 17 Record 定义（请求/响应 DTO）
├── entity/
│   └── UserCredentials.java          # JPA 实体（username, publicKeyY, salt, lastLoginAt）
├── repository/
│   └── UserCredentialsRepository.java # 数据库访问（检查存在性、更新登录时间）
├── service/
│   ├── AuthService.java              # 业务编排（注册、挑战发放、验证登录）
│   ├── crypto/
│   │   └── ZkpService.java           # 核心：Schnorr 挑战生成、验证方程计算
│   ├── kafka/
│   │   └── AuthEventPublisher.java   # 异步发送登录审计事件
│   └── util/
│       └── JwtUtil.java              # JWT Token 生成与解析（JJWT 0.11.5）
├── exception/
│   └── GlobalExceptionHandler.java   # 全局异常处理（屏蔽敏感信息）
└── ZkpAuthApplication.java

docker-compose.yml                    # 基础设施编排（PG主从+Redis集群+Kafka KRaft）
.env.example                          # 环境变量模板
application.yaml                      # Spring 配置
```

---

## 🔐 密码学实现详解

### Schnorr 协议在本项目中的映射

| 数学符号 | 代码变量 | 说明 | 存储位置 |
|---------|---------|------|---------|
| **p** | `SchnorrGroup.p` | 2048-bit 大素数 | 硬编码在 `ZkpCryptoConfig` |
| **q** | `SchnorrGroup.q` | p-1 的素因子 (256-bit) | 硬编码在 `ZkpCryptoConfig` |
| **g** | `SchnorrGroup.g` | 生成元 (通常为 2) | 硬编码在 `ZkpCryptoConfig` |
| **x** | `privateKey` | 用户私钥 | **仅客户端保管** |
| **Y = g^x** | `publicKeyY` | 用户公钥 | PostgreSQL (user_credentials 表) |
| **r** | `randomR` | 服务端随机数 | Redis (TTL=5min，一次性) |
| **R = g^r** | `challenge.R` | 承诺值 | 传输给客户端 |
| **c** | `hashChallenge` | H(R \|\| Y \|\| username) | Fiat-Shamir 启发式 |
| **s** | `proof.s` | r + c*x mod q | 客户端计算并提交 |

### 验证方程（服务端检查）

$$
g^s \stackrel{?}{=} R \cdot Y^c \pmod{p}
$$

**正确性证明**：

- 左式：$g^s = g^{r+cx} = g^r \cdot g^{cx} = R \cdot (g^x)^c = R \cdot Y^c$
- 右式：$R \cdot Y^c$
- 若相等，证明者确实知道 $x = \log_g Y$

---

## 🛡️ 安全特性

1. **零知识性**：验证过程不泄露私钥 $x$ 的任何信息
2. **抗重放**：每个挑战（Challenge）仅可使用一次，验证后立即从 Redis 删除
3. **时效限制**：挑战 5 分钟未使用自动过期
4. **防枚举**：即使用户名不存在，接口也会正常返回假挑战（时间恒定）
5. **审计追踪**：所有登录尝试（成功/失败）通过 Kafka 异步记录，可用于风控分析
6. **传输安全**：建议配合 HTTPS/TLS，防止中间人篡改 $R$ 或 $s$

---

## ⚙️ 配置说明

### 关键环境变量

```env
# PostgreSQL 主从 (docker-compose 内部网络)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/zk_auth
SPRING_DATASOURCE_USERNAME=zkuser
SPRING_DATASOURCE_PASSWORD=zkpass_1314217

# Redis Cluster (必须包含所有主节点)
SPRING_REDIS_CLUSTER_NODES=172.28.0.101:6379,172.28.0.102:6379,172.28.0.103:6379,172.28.0.104:6379,172.28.0.105:6379,172.28.0.106:6379

# Kafka 4.0 (KRaft 模式，无 ZooKeeper)
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092,localhost:9093,localhost:9094
```

### 调整挑战有效期

```yaml
# application.yaml
zkp:
  challenge:
    ttl-minutes: 5        # 挑战过期时间
    max-attempts: 3       # 单用户并发挑战数限制（防 DoS）
```

---

## 💻 客户端集成示例

### JavaScript/TypeScript 客户端 SDK 伪代码

```typescript
const crypto = require('crypto');
const BigInteger = require('jsbn').BigInteger;

const P = new BigInteger('FFFFFFFFFFFFFFFF...', 16);
const Q = new BigInteger('7FFFFFFFFFFFFFFFF...', 16);
const G = new BigInteger('2');

function sha256(data) {
    return crypto.createHash('sha256').update(data).digest();
}

function generateProof(privateKeyHex, challenge, username) {
    const x = new BigInteger(privateKeyHex, 16);
    const R = new BigInteger(challenge.R, 16);
    const Y = G.modPow(x, P);
    
    // 计算 c = H(R || Y || username)
    const hashInput = Buffer.concat([
        Buffer.from(R.toByteArray()),
        Buffer.from(Y.toByteArray()),
        Buffer.from(username, 'utf8')
    ]);
    const c = new BigInteger(sha256(hashInput).toString('hex'), 16).mod(Q);
    
    // 注意：此处需要根据具体协议实现计算 s
    // 本系统要求客户端计算 s，但具体算法取决于协议变体
    
    return {
        challengeId: challenge.challengeId,
        s: s.toString(16),
        clientR: challenge.R  // 必须原样返回服务端提供的 R
    };
}
```

---

## 📊 性能指标（在测呢）

| 指标 | 数值 | 说明 |
|------|------|------|
| **登录延迟** | ~50-100ms | 主要消耗在 1 次 Redis + 1 次 DB 查询 |
| **吞吐量** | ~3000 QPS | 单机（4核8G），受限于大数模幂运算 |
| **挑战存储** | ~200 bytes/Challenge | Redis 内存占用极低 |
| **密码学强度** | 128-bit security | 基于 2048-bit p / 256-bit q |

---

## 🐛 常见问题

**Q: Redis 启动时显示 `Got no valid shards in CLUSTER SHARDS`？**  
A: 这是 Redis 8.4 的 RedisSearch 模块在集群初始化前的正常警告，只要 `redis-bootstrap` 容器最终显示 `[OK] All 16384 slots covered` 即正常。

**Q: 为什么私钥不能找回？**  
A: 零知识证明的本质是服务端不存储任何可推导私钥的信息。如果用户丢失私钥，只能：

1. 通过预存的备用验证方式（如邮箱/手机）重置
2. 重新注册生成新密钥对（旧账户数据需手动迁移）
3. 当然，本项目是没有这些备用手段的，男儿当自强！

**Q: Kafka 启动报错 `AccessDeniedException`？**  
A: 在 Windows Docker Desktop 下，为 Kafka 服务添加 `user: "0:0"` 以 root 权限运行（开发环境）。

---

**安全提示**：本项目为my兴趣用途，生产环境使用需通过第三方安全审计，并建议增加双因素认证（2FA）作为补充hh。