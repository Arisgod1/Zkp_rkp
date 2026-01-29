# ZKP Authentication Pressure Testing Suite

This directory contains JavaScript-based pressure testing scripts for the ZKP (Zero-Knowledge Proof) authentication system.

## Directory Structure

```
pressure_test/
├── docs/
│   └── README.md              # This documentation
├── src/
│   └── zkp_crypto.js          # ZKP cryptographic utilities
├── tests/
│   ├── simple_e2e_test.js     # Simple end-to-end test
│   ├── full_flow_test.js      # Full flow pressure test
│   ├── register_test.js       # Registration pressure test
│   └── login_test.js          # Login pressure test
├── package.json               # Node.js dependencies
└── node_modules/              # Installed packages
```

## Prerequisites

1. Install Node.js (v16 or higher)
2. Install dependencies:
```bash
cd pressure_test
npm install
```

## Configuration

Set environment variables to customize test behavior:

| Variable | Default | Description |
|----------|---------|-------------|
| `ZKP_API_URL` | `http://localhost:8080` | Base URL of the ZKP API |
| `CONCURRENT_USERS` | `10` | Number of concurrent users (full_flow_test) |
| `REQUESTS_PER_USER` | `5` | Requests per user (full_flow_test) |
| `CONCURRENT_REQUESTS` | `50` | Total concurrent requests (register_test) |
| `NUM_USERS` | `10` | Number of users to create (login_test) |
| `LOGINS_PER_USER` | `5` | Login attempts per user (login_test) |

## Test Scripts

### 1. Simple End-to-End Test (`npm run test:e2e`)

Quick validation of ZKP core functionality:
```bash
npm run test:e2e
```

This test validates:
- Group parameters (P, Q, G) are correctly configured
- Q value is properly calculated as (P-1)/2
- Complete ZKP flow works end-to-end

### 2. Full Flow Pressure Test (`npm run test:full`)

Tests the complete ZKP authentication flow under concurrent load:
1. User registration
2. Challenge request (client sends R = g^r)
3. Server responds with challenge c = H(R || Y || username)
4. Client computes proof s = r + c*x mod q
5. Verification and JWT token issuance

```bash
# Default settings (10 users, 5 requests each)
npm run test:full

# Custom settings
CONCURRENT_USERS=20 REQUESTS_PER_USER=10 npm run test:full
```

### 3. Registration Pressure Test (`npm run test:register`)

Tests user registration endpoint under concurrent load:

```bash
# Default settings (50 concurrent registrations)
npm run test:register

# Custom settings
CONCURRENT_REQUESTS=100 npm run test:register
```

### 4. Login Pressure Test (`npm run test:login`)

Tests login flow for pre-registered users:

```bash
# Default settings (10 users, 5 logins each)
npm run test:login

# Custom settings
NUM_USERS=20 LOGINS_PER_USER=10 npm run test:login
```

## Cryptographic Parameters

The ZKP system uses **RFC 3526 1536-bit MODP Group** parameters:

| Parameter | Value | Description |
|-----------|-------|-------------|
| P | 1536-bit safe prime | Modular arithmetic base |
| Q | (P-1)/2 | Subgroup order |
| G | 2 | Generator |

### Performance Characteristics

| Operation | 256-bit (旧) | 1536-bit (当前) | Notes |
|-----------|-------------|-----------------|-------|
| Key Generation | ~10ms | ~60-130ms | 随机数生成 |
| Commitment (R = g^r) | ~15ms | ~100-200ms | 模幂运算 |
| Proof Verification | ~20ms | ~150-300ms | 服务器端验证 |
| Security Level | ~128-bit | ~80-bit | 基于离散对数问题 |

## Test Output

All tests provide detailed statistics:
- Total duration and throughput
- Success/failure counts and rates
- Latency statistics (Average, P50, P95, P99)
- Phase-specific metrics (Registration, Challenge, Verify)

## Example Output

```
═══════════════════════════════════════════════════════════════
           ZKP Full Flow Pressure Test
═══════════════════════════════════════════════════════════════
API URL: http://localhost:8080
Concurrent Users: 10
Requests per User: 5
Total Requests: 50
───────────────────────────────────────────────────────────────

✅ [user_0_req_0_1234567890] Registered successfully
✅ [user_0_req_0_1234567890] Challenge received
✅ [user_0_req_0_1234567890] Login successful, token received
...

═══════════════════════════════════════════════════════════════
                    Test Results
═══════════════════════════════════════════════════════════════
Total Duration: 5234ms
Total Requests: 50
Successful: 50
Failed: 0
Success Rate: 100.00%
Throughput: 9.55 req/s

───────────────────────────────────────────────────────────────
Phase Statistics:
───────────────────────────────────────────────────────────────
Registration - Success: 50, Failed: 0
Challenge    - Success: 50, Failed: 0
Verify       - Success: 50, Failed: 0

───────────────────────────────────────────────────────────────
Latency Statistics (ms):
───────────────────────────────────────────────────────────────
register   Avg:   145.32 | P50:   132.00 | P95:   245.00 | P99:   312.00
challenge  Avg:    89.45 | P50:    78.00 | P95:   156.00 | P99:   198.00
verify     Avg:   112.67 | P50:   102.00 | P95:   189.00 | P99:   234.00
total      Avg:   347.44 | P50:   312.00 | P95:   590.00 | P99:   744.00
═══════════════════════════════════════════════════════════════

✅ Test PASSED
```

## Cryptographic Implementation

The test suite implements the client-side Schnorr ZKP protocol:

1. **Key Generation**: `x` (private), `Y = g^x mod p` (public)
2. **Commitment**: Client generates random `r`, computes `R = g^r mod p`
3. **Challenge**: Server computes `c = H(R || Y || username)`
4. **Proof**: Client computes `s = r + c*x mod q`
5. **Verification**: Server checks `g^s == R * Y^c mod p`

All cryptographic operations use the same group parameters as the server (defined in `src/zkp_crypto.js`).

## Troubleshooting

### 1. 注册超时 (timeout of 120000ms exceeded)

**原因**: 1536位模幂运算计算量大，处理时间较长

**解决方案**:
- 增加超时时间: 在axios配置中设置 `timeout: 300000` (5分钟)
- 减少并发用户数: 设置 `CONCURRENT_USERS=5`
- 优化服务器: 增加线程池大小和连接池配置

### 2. Module Not Found Error

If you see errors like `Cannot find module '../src/zkp_crypto'`, ensure:
- You are running tests from the `pressure_test` directory
- Dependencies are installed: `npm install`
- The `src/zkp_crypto.js` file exists

### 3. Connection Refused

Ensure the ZKP server is running:
```bash
docker-compose up -d
```

Or check if the API URL is correct:
```bash
ZKP_API_URL=http://your-server:8080 npm run test:e2e
```
