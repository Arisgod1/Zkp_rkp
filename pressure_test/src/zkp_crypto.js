/**
 * ZKP Cryptographic Utilities for Client-Side Proof Generation
 * Implements Schnorr protocol: s = r + c*x mod q
 * FIXED: Correct Q value for RFC 3526 1536-bit MODP Group
 */

const { BigInteger } = require('jsbn');
const crypto = require('crypto');

// Schnorr Group Parameters (RFC 3526 1536-bit MODP Group)
// P is a safe prime where P = 2Q + 1
const P = new BigInteger(
    'FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088' +
    'A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302' +
    'B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED' +
    '6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651E' +
    'CE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F8365' +
    '5D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC980' +
    '4F1746C08CA237327FFFFFFFFFFFFFFFF',
    16
);

// Q = (P-1)/2 - computed using native BigInt then converted
const P_BIGINT = BigInt('0x' + P.toString(16));
const Q_BIGINT = (P_BIGINT - BigInt(1)) / BigInt(2);
const Q = new BigInteger(Q_BIGINT.toString(16), 16);

const G = new BigInteger('2');

console.log('ZKP Crypto initialized with:');
console.log(`  P: ${P.toString(16).length} hex chars`);
console.log(`  Q: ${Q.toString(16).length} hex chars`);
console.log(`  G: ${G.toString(16)}`);

/**
 * Generate a random private key
 * @returns {BigInteger} Private key x ∈ [1, q-1]
 */
function generatePrivateKey() {
    let x;
    do {
        // Generate 192 random bytes (1536 bits) to ensure we can get values up to Q
        const bytes = crypto.randomBytes(192);
        x = new BigInteger(bytes.toString('hex'), 16);
    } while (x.compareTo(Q) >= 0 || x.compareTo(BigInteger.ONE) < 0);
    return x;
}

/**
 * Generate public key Y = g^x mod p
 * @param {BigInteger} privateKey - Private key x
 * @returns {BigInteger} Public key Y
 */
function generatePublicKey(privateKey) {
    return G.modPow(privateKey, P);
}

/**
 * Generate random r for commitment
 * @returns {BigInteger} Random r ∈ [1, q-1]
 */
function generateRandomR() {
    let r;
    do {
        // Generate 192 random bytes (1536 bits) to ensure we can get values up to Q
        const bytes = crypto.randomBytes(192);
        r = new BigInteger(bytes.toString('hex'), 16);
    } while (r.compareTo(Q) >= 0 || r.compareTo(BigInteger.ONE) < 0);
    return r;
}

/**
 * Compute commitment R = g^r mod p
 * @param {BigInteger} r - Random value
 * @returns {BigInteger} Commitment R
 */
function computeCommitmentR(r) {
    return G.modPow(r, P);
}

/**
 * Compute challenge hash c = H(R || Y || username)
 * 使用十六进制字符串进行哈希，确保与服务器一致
 * @param {BigInteger} R - Commitment
 * @param {BigInteger} Y - Public key
 * @param {string} username - Username
 * @returns {BigInteger} Challenge c
 */
function computeChallenge(R, Y, username) {
    const hash = crypto.createHash('sha256');
    // 使用十六进制字符串进行哈希，与服务器保持一致
    hash.update(Buffer.from(R.toString(16), 'utf8'));
    hash.update(Buffer.from(Y.toString(16), 'utf8'));
    hash.update(Buffer.from(username, 'utf8'));
    const digest = hash.digest();
    const c = new BigInteger(digest.toString('hex'), 16);
    return c.mod(Q);
}

/**
 * Compute proof s = r + c*x mod q
 * @param {BigInteger} r - Random value
 * @param {BigInteger} c - Challenge
 * @param {BigInteger} x - Private key
 * @returns {BigInteger} Proof s
 */
function computeProofS(r, c, x) {
    const cx = c.multiply(x);
    const rPlusCx = r.add(cx);
    return rPlusCx.mod(Q);
}

/**
 * Generate a random salt
 * @returns {string} Hex-encoded salt
 */
function generateSalt() {
    return crypto.randomBytes(16).toString('hex');
}

module.exports = {
    P,
    Q,
    G,
    generatePrivateKey,
    generatePublicKey,
    generateRandomR,
    computeCommitmentR,
    computeChallenge,
    computeProofS,
    generateSalt
};
