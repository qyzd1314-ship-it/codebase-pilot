package com.yupi.yuaiagent.repo.repository;

import com.yupi.yuaiagent.repo.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRepository extends JpaRepository<Repo, String> {
}
