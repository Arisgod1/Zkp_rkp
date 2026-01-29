/**
 * 简单端到端测试 - 验证ZKP核心功能
 */

const axios = require('axios');
const { BigInteger } = require('jsbn');
const {
    P, Q, G,
    generatePrivateKey,
    generatePublicKey,
    generateRandomR,
    computeCommitmentR,
    computeProofS,
    generateSalt
} = require('../src/zkp_crypto');

const API_URL = 'http://localhost:8080';

console.log('═══════════════════════════════════════════════════════════════');
console.log('           ZKP端到端功能测试');
console.log('═══════════════════════════════════════════════════════════════');

// 验证群参数
console.log('\n📊 群参数验证:');
console.log(`  P: ${P.bitLength()} bits`);
console.log(`  Q: ${Q.bitLength()} bits`);
console.log(`  G: ${G}`);

const expectedQ = P.subtract(BigInteger.ONE).divide(new BigInteger('2'));
console.log(`  Q = (P-1)/2: ${Q.equals(expectedQ) ? '✅ 正确' : '❌ 错误'}`);

async function runE2ETest() {
    const username = `e2e_test_${Date.now()}`;
    console.log(`\n🧪 测试用户: ${username}`);

    try {
        // 1. 生成密钥对
        console.log('\n  1️⃣ 生成密钥对...');
        const x = generatePrivateKey();
        const Y = generatePublicKey(x);
        const salt = generateSalt();
        console.log(`     私钥 x: ${x.toString(16).substring(0, 32)}...`);
        console.log(`     公钥 Y: ${Y.toString(16).substring(0, 32)}...`);

        // 2. 注册
        console.log('\n  2️⃣ 用户注册...');
        const regResponse = await axios.post(`${API_URL}/api/v1/auth/register`, {
            username,
            publicKeyY: Y.toString(16),
            salt
        }, { timeout: 120000 });
        console.log(`     ✅ 注册成功 (状态: ${regResponse.status})`);

        // 3. 生成承诺
        console.log('\n  3️⃣ 生成承诺...');
        const r = generateRandomR();
        const R = computeCommitmentR(r);
        console.log(`     随机数 r: ${r.toString(16).substring(0, 32)}...`);
        console.log(`     承诺 R: ${R.toString(16).substring(0, 32)}...`);

        // 4. 获取挑战
        console.log('\n  4️⃣ 获取挑战...');
        const challengeResponse = await axios.post(`${API_URL}/api/v1/auth/challenge`, {
            username,
            clientR: R.toString(16)
        });
        const { challengeId, c: cHex } = challengeResponse.data;
        const c = new BigInteger(cHex, 16);
        console.log(`     挑战ID: ${challengeId}`);
        console.log(`     挑战值 c: ${c.toString(16).substring(0, 32)}...`);

        // 5. 计算证明
        console.log('\n  5️⃣ 计算证明 s = r + c*x mod q...');
        const s = computeProofS(r, c, x);
        console.log(`     证明 s: ${s.toString(16).substring(0, 32)}...`);

        // 验证本地计算
        console.log('\n  6️⃣ 本地验证Schnorr方程...');
        const leftSide = G.modPow(s, P);
        const Yc = Y.modPow(c, P);
        const rightSide = R.multiply(Yc).mod(P);
        const localValid = leftSide.equals(rightSide);
        console.log(`     g^s mod p: ${leftSide.toString(16).substring(0, 32)}...`);
        console.log(`     R*Y^c mod p: ${rightSide.toString(16).substring(0, 32)}...`);
        console.log(`     本地验证: ${localValid ? '✅ 通过' : '❌ 失败'}`);

        // 6. 服务器验证
        console.log('\n  7️⃣ 服务器验证...');
        try {
            const verifyResponse = await axios.post(`${API_URL}/api/v1/auth/verify`, {
                challengeId,
                s: s.toString(16),
                clientR: R.toString(16),
                username
            });

            if (verifyResponse.data && verifyResponse.data.token) {
                console.log(`     ✅ 服务器验证成功!`);
                console.log(`     🎫 JWT令牌: ${verifyResponse.data.token.substring(0, 50)}...`);
                return { success: true, username, localValid };
            } else {
                console.log(`     ⚠️ 验证通过但未返回令牌`);
                return { success: false, username, error: 'No token returned' };
            }
        } catch (verifyError) {
            console.log(`     ❌ 服务器验证失败`);
            console.log(`     状态码: ${verifyError.response?.status}`);
            console.log(`     错误: ${JSON.stringify(verifyError.response?.data)}`);
            return { success: false, username, error: verifyError.message, localValid };
        }

    } catch (error) {
        console.log(`\n  ❌ 测试失败: ${error.message}`);
        if (error.response) {
            console.log(`     状态码: ${error.response.status}`);
            console.log(`     错误信息: ${JSON.stringify(error.response.data)}`);
        }
        return { success: false, username, error: error.message };
    }
}

// 运行测试
runE2ETest()
    .then(result => {
        console.log('\n═══════════════════════════════════════════════════════════════');
        console.log('                      测试结果总结');
        console.log('═══════════════════════════════════════════════════════════════');
        if (result.success) {
            console.log('✅ 端到端测试通过!');
            console.log('   - 用户注册成功');
            console.log('   - 挑战获取成功');
            console.log('   - 本地Schnorr验证通过');
            console.log('   - 服务器验证通过');
            console.log('   - JWT令牌已颁发');
        } else {
            console.log('❌ 端到端测试失败');
            console.log(`   错误: ${result.error}`);
            if (result.localValid) {
                console.log('   ⚠️ 本地验证通过但服务器验证失败');
                console.log('      可能是服务器内部错误（如Kafka连接问题）');
            }
        }
        process.exit(result.success ? 0 : 1);
    })
    .catch(err => {
        console.error('测试执行错误:', err);
        process.exit(1);
    });
