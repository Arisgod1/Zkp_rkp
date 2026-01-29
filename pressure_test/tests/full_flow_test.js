/**
 * ZKP Full Flow Pressure Test
 * Tests complete registration -> challenge -> verify flow under concurrent load
 */

const axios = require('axios');
const {
    generatePrivateKey,
    generatePublicKey,
    generateRandomR,
    computeCommitmentR,
    computeChallenge,
    computeProofS,
    generateSalt
} = require('../src/zkp_crypto');

const { BigInteger } = require('jsbn');

// Configuration
const BASE_URL = process.env.ZKP_API_URL || 'http://localhost:8080';
const CONCURRENT_USERS = parseInt(process.env.CONCURRENT_USERS) || 10;
const REQUESTS_PER_USER = parseInt(process.env.REQUESTS_PER_USER) || 5;

const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json'
    }
});

// Statistics
const stats = {
    total: 0,
    success: 0,
    failed: 0,
    registerSuccess: 0,
    registerFailed: 0,
    challengeSuccess: 0,
    challengeFailed: 0,
    verifySuccess: 0,
    verifyFailed: 0,
    latencies: {
        register: [],
        challenge: [],
        verify: [],
        total: []
    }
};

function recordLatency(type, startTime) {
    const latency = Date.now() - startTime;
    stats.latencies[type].push(latency);
    return latency;
}

function getAverageLatency(type) {
    const arr = stats.latencies[type];
    if (arr.length === 0) return 0;
    return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function getPercentile(type, percentile) {
    const arr = stats.latencies[type].sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * arr.length) - 1;
    return arr[Math.max(0, index)];
}

/**
 * Register a new user
 */
async function registerUser(username) {
    const startTime = Date.now();
    try {
        const privateKey = generatePrivateKey();
        const publicKey = generatePublicKey(privateKey);
        const salt = generateSalt();

        const response = await api.post('/api/v1/auth/register', {
            username: username,
            publicKeyY: publicKey.toString(16),
            salt: salt
        });

        recordLatency('register', startTime);
        stats.registerSuccess++;

        return {
            success: true,
            username,
            privateKey,
            publicKey,
            salt
        };
    } catch (error) {
        recordLatency('register', startTime);
        stats.registerFailed++;
        return {
            success: false,
            username,
            error: error.response?.data?.message || error.message
        };
    }
}

/**
 * Get challenge from server
 */
async function getChallenge(username, publicKey) {
    const startTime = Date.now();
    try {
        // Client generates random r and computes R = g^r mod p
        const r = generateRandomR();
        const R = computeCommitmentR(r);

        const response = await api.post('/api/v1/auth/challenge', {
            username: username,
            clientR: R.toString(16)
        });

        recordLatency('challenge', startTime);
        stats.challengeSuccess++;

        return {
            success: true,
            challengeId: response.data.challengeId,
            c: new BigInteger(response.data.c, 16),
            r: r,  // Keep r for proof generation
            R: R,
            p: response.data.p,
            q: response.data.q,
            g: response.data.g
        };
    } catch (error) {
        recordLatency('challenge', startTime);
        stats.challengeFailed++;
        return {
            success: false,
            username,
            error: error.response?.data?.message || error.message
        };
    }
}

/**
 * Verify ZKP proof and login
 */
async function verifyAndLogin(username, privateKey, publicKey, challengeData) {
    const startTime = Date.now();
    try {
        // Debug logging
        console.log(`🔍 [${username}] Client Debug:`);
        console.log(`  R: ${challengeData.R.toString(16).substring(0, 32)}...`);
        console.log(`  Y: ${publicKey.toString(16).substring(0, 32)}...`);
        console.log(`  c (from server): ${challengeData.c.toString(16).substring(0, 32)}...`);
        console.log(`  r (private): ${challengeData.r.toString(16).substring(0, 32)}...`);
        console.log(`  x (private): ${privateKey.toString(16).substring(0, 32)}...`);

        // Client computes s = r + c*x mod q
        const s = computeProofS(challengeData.r, challengeData.c, privateKey);
        console.log(`  s: ${s.toString(16).substring(0, 32)}...`);

        const response = await api.post('/api/v1/auth/verify', {
            challengeId: challengeData.challengeId,
            s: s.toString(16),
            clientR: challengeData.R.toString(16),
            username: username
        });

        recordLatency('verify', startTime);
        stats.verifySuccess++;

        return {
            success: true,
            username,
            token: response.data.token,
            expiresIn: response.data.expiresIn
        };
    } catch (error) {
        recordLatency('verify', startTime);
        stats.verifyFailed++;
        return {
            success: false,
            username,
            error: error.response?.data?.message || error.message
        };
    }
}

/**
 * Execute full login flow for one user
 */
async function executeFullFlow(userIndex, requestIndex) {
    const flowStartTime = Date.now();
    const username = `user_${userIndex}_req_${requestIndex}_${Date.now()}`;

    try {
        // Step 1: Register
        const regResult = await registerUser(username);
        if (!regResult.success) {
            console.log(`❌ [${username}] Registration failed: ${regResult.error}`);
            stats.failed++;
            return;
        }
        console.log(`✅ [${username}] Registered successfully`);

        // Step 2: Get Challenge
        const challengeResult = await getChallenge(username, regResult.publicKey);
        if (!challengeResult.success) {
            console.log(`❌ [${username}] Challenge failed: ${challengeResult.error}`);
            stats.failed++;
            return;
        }
        console.log(`✅ [${username}] Challenge received`);

        // Step 3: Verify and Login
        const verifyResult = await verifyAndLogin(
            username,
            regResult.privateKey,
            regResult.publicKey,
            challengeResult
        );

        if (!verifyResult.success) {
            console.log(`❌ [${username}] Verification failed: ${verifyResult.error}`);
            stats.failed++;
            return;
        }

        recordLatency('total', flowStartTime);
        stats.success++;
        console.log(`✅ [${username}] Login successful, token received`);

    } catch (error) {
        recordLatency('total', flowStartTime);
        stats.failed++;
        console.log(`❌ [${username}] Unexpected error: ${error.message}`);
    }
}

/**
 * Run pressure test
 */
async function runPressureTest() {
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('           ZKP Full Flow Pressure Test');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`API URL: ${BASE_URL}`);
    console.log(`Concurrent Users: ${CONCURRENT_USERS}`);
    console.log(`Requests per User: ${REQUESTS_PER_USER}`);
    console.log(`Total Requests: ${CONCURRENT_USERS * REQUESTS_PER_USER}`);
    console.log('───────────────────────────────────────────────────────────────\n');

    const testStartTime = Date.now();

    // Create all user request promises
    const allPromises = [];
    for (let userIdx = 0; userIdx < CONCURRENT_USERS; userIdx++) {
        for (let reqIdx = 0; reqIdx < REQUESTS_PER_USER; reqIdx++) {
            allPromises.push(executeFullFlow(userIdx, reqIdx));
        }
    }

    // Execute all requests concurrently
    await Promise.all(allPromises);

    const testDuration = Date.now() - testStartTime;

    // Print results
    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('                    Test Results');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`Total Duration: ${testDuration}ms`);
    console.log(`Total Requests: ${CONCURRENT_USERS * REQUESTS_PER_USER}`);
    console.log(`Successful: ${stats.success}`);
    console.log(`Failed: ${stats.failed}`);
    console.log(`Success Rate: ${((stats.success / (stats.success + stats.failed)) * 100).toFixed(2)}%`);
    console.log(`Throughput: ${((stats.success + stats.failed) / (testDuration / 1000)).toFixed(2)} req/s`);

    console.log('\n───────────────────────────────────────────────────────────────');
    console.log('Phase Statistics:');
    console.log('───────────────────────────────────────────────────────────────');
    console.log(`Registration - Success: ${stats.registerSuccess}, Failed: ${stats.registerFailed}`);
    console.log(`Challenge    - Success: ${stats.challengeSuccess}, Failed: ${stats.challengeFailed}`);
    console.log(`Verify       - Success: ${stats.verifySuccess}, Failed: ${stats.verifyFailed}`);

    console.log('\n───────────────────────────────────────────────────────────────');
    console.log('Latency Statistics (ms):');
    console.log('───────────────────────────────────────────────────────────────');

    const phases = ['register', 'challenge', 'verify', 'total'];
    phases.forEach(phase => {
        const avg = getAverageLatency(phase);
        const p50 = getPercentile(phase, 50);
        const p95 = getPercentile(phase, 95);
        const p99 = getPercentile(phase, 99);
        console.log(`${phase.padEnd(10)} Avg: ${avg.toFixed(2).padStart(8)} | P50: ${p50.toFixed(2).padStart(8)} | P95: ${p95.toFixed(2).padStart(8)} | P99: ${p99.toFixed(2).padStart(8)}`);
    });

    console.log('═══════════════════════════════════════════════════════════════\n');

    // Exit with error code if success rate is below threshold
    const successRate = stats.success / (stats.success + stats.failed);
    if (successRate < 0.95) {
        console.error('❌ Test FAILED: Success rate below 95%');
        process.exit(1);
    } else {
        console.log('✅ Test PASSED');
        process.exit(0);
    }
}

// Run the test
runPressureTest().catch(error => {
    console.error('Test execution failed:', error);
    process.exit(1);
});
