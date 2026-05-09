package com.yupi.codebasepilot.task.service;

import com.yupi.codebasepilot.repo.entity.Repo;
import com.yupi.codebasepilot.repo.enums.RepoIndexedStatus;
import com.yupi.codebasepilot.repo.repository.RepoRepository;
import com.yupi.codebasepilot.task.dto.AgentTaskCreateRequest;
import com.yupi.codebasepilot.task.dto.AgentTaskResponse;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AgentTaskServiceTest {

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private RepoRepository repoRepository;

    @BeforeEach
    void setUp() {
        agentTaskRepository.deleteAll();
        repoRepository.findById("repo_task_test").ifPresent(repoRepository::delete);

        Repo repo = new Repo();
        repo.setId("repo_task_test");
        repo.setName("axios");
        repo.setUrl("https://github.com/axios/axios.git");
        repo.setBranch("main");
        repo.setLocalPath("D:/tmp/repos/repo_task_test");
        repo.setIndexedStatus(RepoIndexedStatus.INDEXED);
        repo.setFileCount(10);
        repo.setChunkCount(20);
        repoRepository.save(repo);
    }

    @Test
    void shouldRequireRepoIdForCodeBusinessType() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setGoal("登录接口偶尔返回 500，帮我定位可能原�?);
        request.setBusinessType("BUG_DIAGNOSIS");

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> agentTaskService.createTask(request)
        );

        Assertions.assertEquals("repoId is required for code-related businessType.", exception.getMessage());
    }

    @Test
    void shouldCreateCodeTaskWithRepoBinding() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setGoal("登录接口偶尔返回 500，帮我定位可能原�?);
        request.setRepoId("repo_task_test");
        request.setBusinessType("BUG_DIAGNOSIS");

        AgentTaskResponse response = agentTaskService.createTask(request);

        Assertions.assertEquals("repo_task_test", response.getRepoId());
        Assertions.assertEquals("axios", response.getRepoName());
        Assertions.assertEquals("https://github.com/axios/axios.git", response.getRepoUrl());
        Assertions.assertEquals("BUG_DIAGNOSIS", response.getBusinessType());
        Assertions.assertNotNull(response.getTitle());
    }

    @Test
    void shouldKeepLegacyTaskCreationWithoutRepo() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle("General task");
        request.setGoal("Summarize the current task workspace");
        request.setTaskType("GENERAL_AGENT");

        AgentTaskResponse response = agentTaskService.createTask(request);

        Assertions.assertNull(response.getRepoId());
        Assertions.assertNull(response.getRepoName());
        Assertions.assertNull(response.getRepoUrl());
        Assertions.assertNull(response.getBusinessType());
        Assertions.assertEquals("General task", response.getTitle());
    }
}
