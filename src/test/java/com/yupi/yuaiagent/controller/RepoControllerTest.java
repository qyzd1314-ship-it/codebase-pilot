package com.yupi.yuaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.repo.dto.RepoCreateRequest;
import com.yupi.yuaiagent.repo.dto.RepoResponse;
import com.yupi.yuaiagent.repo.service.RepoService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepoController.class)
class RepoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RepoService repoService;

    @Test
    void shouldCreateRepo() throws Exception {
        RepoCreateRequest request = new RepoCreateRequest();
        request.setUrl("https://github.com/example/project.git");
        request.setBranch("main");
        RepoResponse response = RepoResponse.builder()
                .repoId("repo_test123")
                .name("project")
                .url(request.getUrl())
                .branch("main")
                .localPath("D:/tmp/repos/repo_test123")
                .indexedStatus("CLONED")
                .fileCount(0)
                .chunkCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Mockito.when(repoService.createRepo(Mockito.any(RepoCreateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoId").value("repo_test123"))
                .andExpect(jsonPath("$.indexedStatus").value("CLONED"))
                .andExpect(jsonPath("$.branch").value("main"));
    }

    @Test
    void shouldListRepos() throws Exception {
        RepoResponse response = RepoResponse.builder()
                .repoId("repo_test123")
                .name("project")
                .url("https://github.com/example/project.git")
                .branch("main")
                .localPath("D:/tmp/repos/repo_test123")
                .indexedStatus("CLONED")
                .fileCount(0)
                .chunkCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Mockito.when(repoService.listRepos()).thenReturn(List.of(response));

        mockMvc.perform(get("/repos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repoId").value("repo_test123"))
                .andExpect(jsonPath("$[0].name").value("project"));
    }

    @Test
    void shouldReturnBadRequestWhenCreateFails() throws Exception {
        RepoCreateRequest request = new RepoCreateRequest();
        request.setUrl("http://invalid.example.com/project.git");
        Mockito.when(repoService.createRepo(Mockito.any(RepoCreateRequest.class)))
                .thenThrow(new IllegalArgumentException("Only GitHub HTTPS URLs are allowed."));

        mockMvc.perform(post("/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only GitHub HTTPS URLs are allowed."));
    }
}
