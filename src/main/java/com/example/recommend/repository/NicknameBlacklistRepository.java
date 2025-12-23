package com.example.recommend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.recommend.domain.NicknameBlacklist;

import java.util.List;

public interface NicknameBlacklistRepository
        extends JpaRepository<NicknameBlacklist, Long> {

    List<NicknameBlacklist> findAll();
}
