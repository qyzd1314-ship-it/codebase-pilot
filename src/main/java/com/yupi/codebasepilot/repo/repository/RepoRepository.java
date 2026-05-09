package com.yupi.codebasepilot.repo.repository;

import com.yupi.codebasepilot.repo.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRepository extends JpaRepository<Repo, String> {
}
