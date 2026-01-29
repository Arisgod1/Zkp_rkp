/**
 * ZKP Registration Pressure Test
 * Tests user registration endpoint under concurrent load
 */

const axios = require('axios');
const { generatePrivateKey, generatePublicKey, generateSalt } = require('../src/zkp_crypto');

// Configuration
const BASE_URL = process.env.ZKP_API_URL || 'http://localhost:8080';
const CONCURRENT_REQUESTS = parseInt(process.env.CONCURRENT_REQUESTS) || 50;

const api = axios.create({
    baseURL: BASE_URL,
    timeout: 10000,
    headers: {
        'Content-Type': 'application/json'
    }
});

// Statistics
const stats = {
    success: 0,
    failed: 0,
    latencies: []
};

async function registerUser(index) {
    const startTime = Date.now();
    const username = `test_user_${index}_${Date.now()}`;

    try {
        const privateKey = generatePrivateKey();
        const publicKey = generatePublicKey(privateKey);
        const salt = generateSalt();

        const response = await api.post('/api/v1/auth/register', {
            username: username,
            publicKeyY: publicKey.toString(16),
            salt: salt
        });

        const latency = Date.now() - startTime;
        stats.latencies.push(latency);
        stats.success++;

        console.log(`✅ [${username}] Registered in ${latency}ms`);
        return { success: true, username, latency };
    } catch (error) {
        const latency = Date.now() - startTime;
        stats.latencies.push(latency);
        stats.failed++;

        const errorMsg = error.response?.data?.message || error.message;
        console.log(`❌ [${username}] Failed: ${errorMsg}`);
        return { success: false, username, error: errorMsg };
    }
}

async function runTest() {
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('           ZKP Registration Pressure Test');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`API URL: ${BASE_URL}`);
    console.log(`Concurrent Requests: ${CONCURRENT_REQUESTS}`);
    console.log('───────────────────────────────────────────────────────────────\n');

    const testStartTime = Date.now();

    // Execute all requests concurrently
    const promises = [];
    for (let i = 0; i < CONCURRENT_REQUESTS; i++) {
        promises.push(registerUser(i));
    }

    await Promise.all(promises);

    const testDuration = Date.now() - testStartTime;

    // Calculate statistics
    const avgLatency = stats.latencies.reduce((a, b) => a + b, 0) / stats.latencies.length;
    const sortedLatencies = [...stats.latencies].sort((a, b) => a - b);
    const p50 = sortedLatencies[Math.floor(sortedLatencies.length * 0.5)];
    const p95 = sortedLatencies[Math.floor(sortedLatencies.length * 0.95)];
    const p99 = sortedLatencies[Math.floor(sortedLatencies.length * 0.99)];

    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('                    Test Results');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`Total Duration: ${testDuration}ms`);
    console.log(`Successful: ${stats.success}`);
    console.log(`Failed: ${stats.failed}`);
    console.log(`Success Rate: ${((stats.success / CONCURRENT_REQUESTS) * 100).toFixed(2)}%`);
    console.log(`Throughput: ${(CONCURRENT_REQUESTS / (testDuration / 1000)).toFixed(2)} req/s`);
    console.log('\nLatency Statistics:');
    console.log(`  Average: ${avgLatency.toFixed(2)}ms`);
    console.log(`  P50: ${p50}ms`);
    console.log(`  P95: ${p95}ms`);
    console.log(`  P99: ${p99}ms`);
    console.log('═══════════════════════════════════════════════════════════════\n');

    if (stats.success / CONCURRENT_REQUESTS < 0.95) {
        console.error('❌ Test FAILED');
        process.exit(1);
    } else {
        console.log('✅ Test PASSED');
        process.exit(0);
    }
}

runTest().catch(error => {
    console.error('Test failed:', error);
    process.exit(1);
});
