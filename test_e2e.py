#!/usr/bin/env python3
"""
ZKP认证系统端到端测试
测试完整的注册-挑战-验证流程
"""
import requests
import hashlib
import random
import sys

BASE_URL = "http://localhost:8080/api/v1"

def generate_random_hex(length):
    """生成指定长度的随机十六进制字符串"""
    return ''.join(random.choices('0123456789abcdef', k=length))

def test_health_check():
    """测试健康检查端点"""
    print("\n=== 测试1: 健康检查 ===")
    try:
        response = requests.get(f"{BASE_URL}/actuator/health", timeout=5)
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            print(f"响应: {response.json()}")
            print("✓ 健康检查通过")
            return True
        else:
            print(f"✗ 健康检查失败: {response.text}")
            return False
    except Exception as e:
        print(f"✗ 健康检查异常: {e}")
        return False

def test_register():
    """测试用户注册"""
    print("\n=== 测试2: 用户注册 ===")
    username = f"testuser_{generate_random_hex(6)}"
    # 生成模拟的公钥Y (256位十六进制)
    public_key_y = generate_random_hex(64)
    
    payload = {
        "username": username,
        "publicKeyY": public_key_y
    }
    
    try:
        response = requests.post(
            f"{BASE_URL}/api/auth/register",
            json=payload,
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        print(f"请求: {payload}")
        print(f"响应: {response.text}")
        
        if response.status_code == 200:
            print(f"✓ 用户注册成功: {username}")
            return username, public_key_y
        elif response.status_code == 409:
            print(f"! 用户已存在: {username}")
            return username, public_key_y
        else:
            print(f"✗ 注册失败: {response.text}")
            return None, None
    except Exception as e:
        print(f"✗ 注册异常: {e}")
        return None, None

def test_request_challenge(username):
    """测试请求挑战"""
    print(f"\n=== 测试3: 请求挑战 (用户: {username}) ===")
    
    payload = {"username": username}
    
    try:
        response = requests.post(
            f"{BASE_URL}/api/auth/challenge",
            json=payload,
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        print(f"响应: {response.text}")
        
        if response.status_code == 200:
            data = response.json()
            challenge_id = data.get("challengeId")
            r = data.get("r")
            print(f"✓ 挑战请求成功")
            print(f"  - Challenge ID: {challenge_id}")
            print(f"  - R: {r[:20]}..." if r and len(r) > 20 else f"  - R: {r}")
            return challenge_id, r
        else:
            print(f"✗ 挑战请求失败: {response.text}")
            return None, None
    except Exception as e:
        print(f"✗ 挑战请求异常: {e}")
        return None, None

def test_verify_proof(username, challenge_id, r):
    """测试验证证明"""
    print(f"\n=== 测试4: 验证证明 (用户: {username}) ===")
    
    # 生成模拟的ZKP证明参数
    # 在实际场景中，这些应该是客户端使用私钥计算得出的
    r_commitment = generate_random_hex(64)  # R = g^r mod p
    s_proof = generate_random_hex(64)       # s = r + c*x mod q
    
    payload = {
        "username": username,
        "challengeId": challenge_id,
        "r": r_commitment,
        "s": s_proof
    }
    
    try:
        response = requests.post(
            f"{BASE_URL}/api/auth/verify",
            json=payload,
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        print(f"请求: {payload}")
        print(f"响应: {response.text}")
        
        if response.status_code == 200:
            data = response.json()
            token = data.get("token")
            print(f"✓ 验证成功，获取到JWT Token")
            print(f"  - Token: {token[:50]}..." if token and len(token) > 50 else f"  - Token: {token}")
            return token
        elif response.status_code == 401:
            print(f"✗ 验证失败 (401): 证明无效")
            return None
        elif response.status_code == 410:
            print(f"✗ 挑战已过期 (410)")
            return None
        else:
            print(f"✗ 验证失败: {response.status_code} - {response.text}")
            return None
    except Exception as e:
        print(f"✗ 验证异常: {e}")
        return None

def test_replay_attack(username, challenge_id, r, s):
    """测试重放攻击防护"""
    print(f"\n=== 测试5: 重放攻击测试 (用户: {username}) ===")
    
    payload = {
        "username": username,
        "challengeId": challenge_id,
        "r": r,
        "s": s
    }
    
    try:
        response = requests.post(
            f"{BASE_URL}/api/auth/verify",
            json=payload,
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        print(f"响应: {response.text}")
        
        if response.status_code == 410:
            print("✓ 重放攻击被正确拦截 (410 Gone)")
            return True
        else:
            print(f"✗ 重放攻击防护异常: 期望410，实际{response.status_code}")
            return False
    except Exception as e:
        print(f"✗ 重放测试异常: {e}")
        return False

def test_invalid_user():
    """测试无效用户处理"""
    print(f"\n=== 测试6: 无效用户测试 ===")
    
    payload = {"username": "nonexistent_user_12345"}
    
    try:
        response = requests.post(
            f"{BASE_URL}/api/auth/challenge",
            json=payload,
            timeout=10
        )
        print(f"状态码: {response.status_code}")
        print(f"响应: {response.text}")
        
        # 应该返回401，不暴露用户是否存在
        if response.status_code == 401:
            print("✓ 无效用户处理正确 (401 Unauthorized)")
            return True
        else:
            print(f"! 返回状态码: {response.status_code} (注意: 应该返回401以保护用户隐私)")
            return False
    except Exception as e:
        print(f"✗ 无效用户测试异常: {e}")
        return False

def main():
    """主测试流程"""
    print("=" * 60)
    print("ZKP认证系统端到端测试")
    print("=" * 60)
    
    results = []
    
    # 测试1: 健康检查
    results.append(("健康检查", test_health_check()))
    
    # 测试2: 用户注册
    username, public_key_y = test_register()
    results.append(("用户注册", username is not None))
    
    if not username:
        print("\n✗ 注册失败，终止后续测试")
        sys.exit(1)
    
    # 测试3: 请求挑战
    challenge_id, r = test_request_challenge(username)
    results.append(("请求挑战", challenge_id is not None))
    
    if not challenge_id:
        print("\n✗ 挑战请求失败，终止后续测试")
        sys.exit(1)
    
    # 测试4: 验证证明
    token = test_verify_proof(username, challenge_id, r)
    results.append(("验证证明", token is not None))
    
    # 测试5: 重放攻击 (使用相同的challenge_id)
    if challenge_id and r:
        s = generate_random_hex(64)
        results.append(("重放攻击防护", test_replay_attack(username, challenge_id, r, s)))
    
    # 测试6: 无效用户
    results.append(("无效用户处理", test_invalid_user()))
    
    # 打印测试摘要
    print("\n" + "=" * 60)
    print("测试摘要")
    print("=" * 60)
    
    passed = sum(1 for _, result in results if result)
    total = len(results)
    
    for test_name, result in results:
        status = "✓ 通过" if result else "✗ 失败"
        print(f"{test_name}: {status}")
    
    print("-" * 60)
    print(f"总计: {passed}/{total} 测试通过")
    
    if passed == total:
        print("\n🎉 所有测试通过！")
        return 0
    else:
        print(f"\n⚠️  {total - passed} 个测试失败")
        return 1

if __name__ == "__main__":
    sys.exit(main())
