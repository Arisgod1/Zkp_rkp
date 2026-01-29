/**
 * ZKP Login Pressure Test
 * Tests login flow for pre-registered users under concurrent load
 */

const axios = require('axios');
const {
    generatePrivateKey,
    generatePublicKey,
    generateRandomR,
    computeCommitmentR,
    computeProofS,
    generateSalt
} = require('../src/zkp_crypto');

const { BigInteger } = require('jsbn');

// Configuration
const BASE_URL = process.env.ZKP_API_URL || 'http://localhost:8080';
const NUM_USERS = parseInt(process.env.NUM_USERS) || 10;
const LOGINS_PER_USER = parseInt(process.env.LOGINS_PER_USER) || 5;

const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json'
    }
});

// Store registered users
const registeredUsers = [];

// Statistics
const stats = {
    success: 0,
    failed: 0,
    challengeSuccess: 0,
    challengeFailed: 0,
    verifySuccess: 0,
    verifyFailed: 0,
    latencies: {
        challenge: [],
        verify: [],
        total: []
    }
};

/**
 * Register a batch of users first
 */
async function setupUsers() {
    console.log(`Setting up ${NUM_USERS} test users...`);

    for (let i = 0; i < NUM_USERS; i++) {
        const username = `login_test_user_${i}_${Date.now()}`;
        const privateKey = generatePrivateKey();
        const publicKey = generatePublicKey(privateKey);
        const salt = generateSalt();

        try {
            await api.post('/api/v1/auth/register', {
                username: username,
                publicKeyY: publicKey.toString(16),
                salt: salt
            });

            registeredUsers.push({
                username,
                privateKey,
                publicKey,
                salt
            });

            console.log(`  ✅ Registered: ${username}`);
        } catch (error) {
            console.log(`  ❌ Failed to register: ${username} - ${error.message}`);
        }
    }

    console.log(`Successfully registered ${registeredUsers.length} users\n`);
}

/**
 * Execute login flow for a user
 */
async function loginUser(user, loginIndex) {
    const flowStartTime = Date.now();

    try {
        // Step 1: Get Challenge
        const challengeStartTime = Date.now();
        const r = generateRandomR();
        const R = computeCommitmentR(r);

        const challengeResponse = await api.post('/api/v1/auth/challenge', {
            username: user.username,
            clientR: R.toString(16)
        });

        const challengeLatency = Date.now() - challengeStartTime;
        stats.latencies.challenge.push(challengeLatency);
        stats.challengeSuccess++;

        const challengeId = challengeResponse.data.challengeId;
        const c = new BigInteger(challengeResponse.data.c, 16);

        // Step 2: Verify
        const verifyStartTime = Date.now();
        const s = computeProofS(r, c, user.privateKey);

        const verifyResponse = await api.post('/api/v1/auth/verify', {
            challengeId: challengeId,
            s: s.toString(16),
            clientR: R.toString(16),
            username: user.username
        });

        const verifyLatency = Date.now() - verifyStartTime;
        stats.latencies.verify.push(verifyLatency);
        stats.verifySuccess++;

        const totalLatency = Date.now() - flowStartTime;
        stats.latencies.total.push(totalLatency);
        stats.success++;

        console.log(`✅ [${user.username}] Login #${loginIndex + 1} successful (${totalLatency}ms)`);
        return { success: true, latency: totalLatency };

    } catch (error) {
        const totalLatency = Date.now() - flowStartTime;
        stats.latencies.total.push(totalLatency);
        stats.failed++;

        const errorMsg = error.response?.data?.message || error.message;
        console.log(`❌ [${user.username}] Login #${loginIndex + 1} failed: ${errorMsg}`);
        return { success: false, error: errorMsg };
    }
}

/**
 * Run login pressure test
 */
async function runTest() {
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('           ZKP Login Pressure Test');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`API URL: ${BASE_URL}`);
    console.log(`Users: ${NUM_USERS}`);
    console.log(`Logins per User: ${LOGINS_PER_USER}`);
    console.log(`Total Login Attempts: ${NUM_USERS * LOGINS_PER_USER}`);
    console.log('───────────────────────────────────────────────────────────────\n');

    // Setup: Register users
    await setupUsers();

    if (registeredUsers.length === 0) {
        console.error('No users registered, cannot proceed with login test');
        process.exit(1);
    }

    const testStartTime = Date.now();

    // Execute login flows concurrently
    const allPromises = [];
    for (const user of registeredUsers) {
        for (let i = 0; i < LOGINS_PER_USER; i++) {
            allPromises.push(loginUser(user, i));
        }
    }

    await Promise.all(allPromises);

    const testDuration = Date.now() - testStartTime;
    const totalRequests = NUM_USERS * LOGINS_PER_USER;

    // Calculate statistics
    const calcStats = (arr) => {
        if (arr.length === 0) return { avg: 0, p50: 0, p95: 0, p99: 0 };
        const sorted = [...arr].sort((a, b) => a - b);
        const avg = arr.reduce((a, b) => a + b, 0) / arr.length;
        return {
            avg,
            p50: sorted[Math.floor(sorted.length * 0.5)],
            p95: sorted[Math.floor(sorted.length * 0.95)],
            p99: sorted[Math.floor(sorted.length * 0.99)]
        };
    };

    const challengeStats = calcStats(stats.latencies.challenge);
    const verifyStats = calcStats(stats.latencies.verify);
    const totalStats = calcStats(stats.latencies.total);

    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('                    Test Results');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`Total Duration: ${testDuration}ms`);
    console.log(`Successful: ${stats.success}`);
    console.log(`Failed: ${stats.failed}`);
    console.log(`Success Rate: ${((stats.success / totalRequests) * 100).toFixed(2)}%`);
    console.log(`Throughput: ${(totalRequests / (testDuration / 1000)).toFixed(2)} req/s`);

    console.log('\n───────────────────────────────────────────────────────────────');
    console.log('Phase Statistics:');
    console.log('───────────────────────────────────────────────────────────────');
    console.log(`Challenge - Success: ${stats.challengeSuccess}, Failed: ${stats.challengeFailed}`);
    console.log(`Verify    - Success: ${stats.verifySuccess}, Failed: ${stats.verifyFailed}`);

    console.log('\n───────────────────────────────────────────────────────────────');
    console.log('Latency Statistics (ms):');
    console.log('───────────────────────────────────────────────────────────────');
    console.log(`Challenge  Avg: ${challengeStats.avg.toFixed(2).padStart(8)} | P50: ${challengeStats.p50.toFixed(2).padStart(8)} | P95: ${challengeStats.p95.toFixed(2).padStart(8)} | P99: ${challengeStats.p99.toFixed(2).padStart(8)}`);
    console.log(`Verify     Avg: ${verifyStats.avg.toFixed(2).padStart(8)} | P50: ${verifyStats.p50.toFixed(2).padStart(8)} | P95: ${verifyStats.p95.toFixed(2).padStart(8)} | P99: ${verifyStats.p99.toFixed(2).padStart(8)}`);
    console.log(`Total      Avg: ${totalStats.avg.toFixed(2).padStart(8)} | P50: ${totalStats.p50.toFixed(2).padStart(8)} | P95: ${totalStats.p95.toFixed(2).padStart(8)} | P99: ${totalStats.p99.toFixed(2).padStart(8)}`);

    console.log('═══════════════════════════════════════════════════════════════\n');

    if (stats.success / totalRequests < 0.95) {
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
