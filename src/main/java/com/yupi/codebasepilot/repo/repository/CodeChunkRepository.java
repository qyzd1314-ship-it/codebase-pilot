package com.yupi.codebasepilot.repo.repository;

import com.yupi.codebasepilot.repo.entity.CodeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, String> {

    void deleteByRepoId(String repoId);

    List<CodeChunk> findByRepoIdOrderByFilePathAscStartLineAsc(String repoId);
}
