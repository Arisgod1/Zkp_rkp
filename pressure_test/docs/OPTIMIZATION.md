# ZKP 系统性能优化记录

## 问题描述

在高并发压力测试（`npm run test:full`）时，系统出现以下问题：

1. **Redis 连接超时**: 默认 2 秒超时时间太短，高并发下连接池耗尽
2. **ZKP 验证阻塞**: 1536 位模幂运算在事件循环中执行，阻塞其他请求
3. **线程池不足**: 默认弹性线程池配置无法满足高并发加密运算需求

## 优化措施

### 1. Redis 连接池优化

**文件**: `application.yaml`

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 50      # 从 8 增加到 50
          max-idle: 30        # 从 20 增加到 30
          min-idle: 10        # 从 5 增加到 10
          max-wait: 5000ms    # 新增：最大等待时间
        shutdown-timeout: 200ms
      timeout: 10000ms        # 从 2000ms 增加到 10000ms
```

**优化效果**:
- 连接池容量提升 6 倍
- 超时时间延长 5 倍
- 支持更高的并发连接数

### 2. ZKP 验证异步化

**文件**: `ZkpService.java`

将耗时的模幂运算从事件循环移到弹性线程池：

```java
// 优化前：在事件循环中执行，阻塞其他请求
BigInteger leftSide = group.g().modPow(proof.s(), group.p());
BigInteger Yc = publicKeyY.modPow(c, group.p());

// 优化后：放到弹性线程池异步执行
return Mono.fromCallable(() -> {
    BigInteger leftSide = group.g().modPow(proof.s(), group.p());
    BigInteger Yc = publicKeyY.modPow(c, group.p());
    BigInteger rightSide = proof.clientR().multiply(Yc).mod(group.p());
    return leftSide.equals(rightSide);
}).subscribeOn(Schedulers.boundedElastic())
```

**优化效果**:
- 验证阶段延迟从 30000ms (超时) 降低到 ~400ms
- 事件循环不再被阻塞
- 系统可以处理更多并发请求

### 3. 弹性线程池配置

**文件**: `application.yaml`

```yaml
reactor:
  schedulers:
    boundedElastic:
      threadCap: 100        # 线程上限
      queueCap: 100000      # 任务队列容量
```

**优化效果**:
- 支持最多 100 个线程执行加密运算
- 任务队列可容纳 10 万个等待任务
- 避免高并发下任务丢失

## 测试结果对比

### 优化前

```
Total Requests: 50
Successful: 0
Failed: 50
Success Rate: 0.00%

Phase Statistics:
Registration - Success: 50, Failed: 0
Challenge    - Success: 50, Failed: 0
Verify       - Success: 0, Failed: 50  ← 全部超时
```

### 优化后

```
Total Requests: 50
Successful: 50
Failed: 0
Success Rate: 100.00%
Throughput: 4.72 req/s

Phase Statistics:
Registration - Success: 50, Failed: 0
Challenge    - Success: 50, Failed: 0
Verify       - Success: 50, Failed: 0  ← 全部通过

Latency Statistics (ms):
register   Avg:  6234.06 | P50:  6296.00 | P95:  8901.00 | P99:  9573.00
challenge  Avg:  2311.76 | P50:  2198.00 | P95:  4134.00 | P99:  4331.00
verify     Avg:   392.10 | P50:   382.00 | P95:   479.00 | P99:   496.00
total      Avg:  8939.04 | P50:  8913.00 | P95: 10243.00 | P99: 10394.00
```

## 关键指标

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| 成功率 | 0% | 100% | +100% |
| 验证延迟 | 30000ms (超时) | ~400ms | -98.7% |
| 吞吐量 | 0 req/s | 4.72 req/s | +4.72 |
| 并发支持 | 几乎无法并发 | 支持 10+ 并发 | 显著提升 |

## 进一步优化建议

1. **使用 Montgomery 乘法**: 优化大数模幂运算性能
2. **缓存公钥**: 避免重复查询数据库
3. **连接池预热**: 应用启动时预热 Redis 和数据库连接
4. **水平扩展**: 部署多个应用实例，使用负载均衡
5. **硬件加速**: 使用支持大数运算的硬件或 GPU 加速

## 相关文件

- `src/main/resources/application.yaml` - Redis 和线程池配置
- `src/main/java/com/tmd/zkp_rkp/service/crypto/ZkpService.java` - ZKP 验证异步化
- `pressure_test/tests/full_flow_test.js` - 压力测试脚本
