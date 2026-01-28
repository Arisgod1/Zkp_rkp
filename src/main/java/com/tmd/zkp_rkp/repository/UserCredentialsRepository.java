package com.tmd.zkp_rkp.repository;

import com.tmd.zkp_rkp.entity.UserCredentials;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @Description
 * @Author Bluegod
 * @Date 2026/1/28
 */
@Repository
public interface UserCredentialsRepository extends JpaRepository<UserCredentials, String> {

    Optional<UserCredentials> findByUsername(String username);

    boolean existsByUsername(String username);

    @Modifying
    @Query("UPDATE UserCredentials u SET u.lastLoginAt = :time WHERE u.username = :username")
    void updateLastLoginTime(@Param("username") String username, @Param("time")LocalDateTime time);
}
