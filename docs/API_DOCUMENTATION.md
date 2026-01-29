# ZKP零知识证明认证系统 - API文档

## 概述

本文档描述了基于Schnorr协议的零知识证明（ZKP）认证系统的REST API接口。该系统允许用户在不传输私钥的情况下完成身份验证。

## 基础信息

- **Base URL**: `http://localhost:8080/api/v1`
- **Content-Type**: `application/json`
- **协议**: HTTP/1.1 或 HTTP/2

## 认证流程

ZKP认证包含三个阶段：

1. **注册阶段**: 用户注册公钥
2. **挑战阶段**: 客户端请求挑战值
3. **验证阶段**: 客户端提交证明完成认证

### 数学原理

**Schnorr协议**: 
- 私钥: x (随机数)
- 公钥: Y = g^x mod p
- 承诺: R = g^r mod p (r为临时随机数)
- 挑战: c = H(R || Y || username)
- 证明: s = r + c·x mod q
- 验证: g^s ≡ R · Y^c (mod p)

## API端点

### 1. 用户注册

**POST** `/auth/register`

注册用户并存储公钥。

#### 请求参数

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| username | string | 是 | 用户名(3-32字符，字母数字下划线) |
| publicKeyY | string | 是 | 公钥Y的十六进制表示 |
| salt | string | 是 | 盐值 |

#### 请求示例

```json
{
  "username": "alice",
  "publicKeyY": "a1b2c3d4e5f6789012345678901234567890abcdef...",
  "salt": "random_salt_value"
}
```

#### 响应

- **201 Created**: 注册成功
- **400 Bad Request**: 请求参数无效
- **409 Conflict**: 用户名已存在

---

### 2. 请求挑战

**POST** `/auth/challenge`

请求认证挑战值。

#### 请求参数

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| username | string | 是 | 用户名 |
| clientR | string | 是 | 客户端承诺值R的十六进制表示 |

#### 请求示例

```json
{
  "username": "alice",
  "clientR": "abcd1234ef5678901234567890abcdef12345678..."
}
```

#### 响应示例 (200 OK)

```json
{
  "challengeId": "f08cee12-90c8-4522-a487-6d0cba4cc622",
  "c": "cf7fd2f169137365bfab7a56ed3742e5b6d2a925...",
  "p": "ffffffffffffffffc90fdaa22168c234c4c6628b80dc...",
  "q": "7fffffffffffffffe487ed5110b4611a62633145c06e...",
  "g": "2"
}
```

#### 响应字段说明

| 字段 | 描述 |
|------|------|
| challengeId | 挑战唯一标识符 |
| c | 挑战值 (十六进制) |
| p | Schnorr群参数p (十六进制) |
| q | Schnorr群参数q (十六进制) |
| g | 生成器g (十六进制) |

---

### 3. 验证登录

**POST** `/auth/verify`

提交ZKP证明完成认证。

#### 请求参数

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| username | string | 是 | 用户名 |
| challengeId | string | 是 | 挑战ID |
| s | string | 是 | 证明值s的十六进制表示 |
| clientR | string | 是 | 承诺值R的十六进制表示 |

#### 请求示例

```json
{
  "username": "alice",
  "challengeId": "f08cee12-90c8-4522-a487-6d0cba4cc622",
  "s": "1234abcd5678ef9012345678901234567890abcdef...",
  "clientR": "abcd1234ef5678901234567890abcdef12345678..."
}
```

#### 响应示例 (200 OK)

```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "type": "Bearer",
  "username": "alice",
  "expiresIn": 86400
}
```

#### 响应字段说明

| 字段 | 描述 |
|------|------|
| token | JWT访问令牌 |
| type | 令牌类型 (Bearer) |
| username | 用户名 |
| expiresIn | 令牌过期时间(秒) |

#### 错误响应

- **401 Unauthorized**: 验证失败（无效证明或挑战过期）

---

## 错误处理

### 错误响应格式

```json
{
  "code": "INVALID_PROOF",
  "message": "Zero-knowledge proof verification failed",
  "timestamp": 1706510400000
}
```

### 错误码列表

| 错误码 | HTTP状态 | 描述 |
|--------|----------|------|
| INVALID_REQUEST | 400 | 请求参数无效 |
| USER_EXISTS | 409 | 用户已存在 |
| INVALID_PROOF | 401 | ZKP验证失败 |
| CHALLENGE_EXPIRED | 401 | 挑战已过期 |
| REPLAY_DETECTED | 401 | 检测到重放攻击 |
| USER_NOT_FOUND | 401 | 用户不存在（模糊处理） |
| INTERNAL_ERROR | 500 | 服务器内部错误 |

---

## 安全特性

### 1. 防重放攻击
- 每个挑战只能使用一次
- 验证成功后挑战立即失效
- 挑战有效期5分钟

### 2. 防用户枚举
- 对不存在用户返回假挑战
- 真假挑战响应时间一致
- 不暴露用户是否存在

### 3. 参数验证
- 所有输入参数严格验证
- 十六进制格式检查
- 数值范围验证

### 4. 日志脱敏
- 不记录敏感参数(r, s, x)
- 只记录挑战ID和结果
- 用户标识哈希处理

---

## 客户端实现示例

### JavaScript/TypeScript

```typescript
// 1. 生成密钥对
const x = BigInt('0x' + crypto.randomBytes(32).toString('hex')); // 私钥
const Y = modPow(g, x, p); // 公钥

// 2. 注册
await fetch('/api/v1/auth/register', {
  method: 'POST',
  body: JSON.stringify({
    username: 'alice',
    publicKeyY: Y.toString(16),
    salt: crypto.randomBytes(16).toString('hex')
  })
});

// 3. 请求挑战
const r = BigInt('0x' + crypto.randomBytes(32).toString('hex'));
const R = modPow(g, r, p);

const challengeResp = await fetch('/api/v1/auth/challenge', {
  method: 'POST',
  body: JSON.stringify({
    username: 'alice',
    clientR: R.toString(16)
  })
}).then(r => r.json());

// 4. 计算证明
const c = BigInt('0x' + challengeResp.c);
const s = (r + c * x) % q;

// 5. 验证登录
const authResp = await fetch('/api/v1/auth/verify', {
  method: 'POST',
  body: JSON.stringify({
    username: 'alice',
    challengeId: challengeResp.challengeId,
    s: s.toString(16),
    clientR: R.toString(16)
  })
}).then(r => r.json());

// 存储token
localStorage.setItem('token', authResp.token);
```

---

## 群参数

系统使用RFC 3526 1536-bit MODP Group:

```
p = FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088
    A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302
    B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED
    6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651E
    CE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F8365
    5D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC980
    4F1746C08CA237327FFFFFFFFFFFFFFFF

q = (p - 1) / 2
g = 2
```

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-01-29 | 初始版本，实现基础ZKP认证 |

---

## 相关文档

- [架构设计](./ARCHITECTURE.md)
- [部署指南](./DEPLOYMENT.md)
- [测试报告](./TEST_REPORT.md)
