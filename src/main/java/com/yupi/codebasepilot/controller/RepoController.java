package com.yupi.codebasepilot.controller;

import com.yupi.codebasepilot.repo.dto.CodeSearchRequest;
import com.yupi.codebasepilot.repo.dto.CodeSearchResponse;
import com.yupi.codebasepilot.repo.dto.RepoCreateRequest;
import com.yupi.codebasepilot.repo.dto.RepoResponse;
import com.yupi.codebasepilot.repo.service.CodeSearchService;
import com.yupi.codebasepilot.repo.service.RepoIndexService;
import com.yupi.codebasepilot.repo.service.RepoService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/repos")
public class RepoController {

    private final RepoService repoService;
    private final RepoIndexService repoIndexService;
    private final CodeSearchService codeSearchService;

    public RepoController(RepoService repoService,
                          RepoIndexService repoIndexService,
                          CodeSearchService codeSearchService) {
        this.repoService = repoService;
        this.repoIndexService = repoIndexService;
        this.codeSearchService = codeSearchService;
    }

    @PostMapping
    public RepoResponse createRepo(@RequestBody RepoCreateRequest request) {
        return repoService.createRepo(request);
    }

    @GetMapping
    public List<RepoResponse> listRepos() {
        return repoService.listRepos();
    }

    @GetMapping("/{repoId}")
    public RepoResponse getRepo(@PathVariable String repoId) {
        return repoService.getRepo(repoId);
    }

    @PostMapping("/{repoId}/index")
    public RepoResponse indexRepo(@PathVariable String repoId) {
        return repoIndexService.indexRepo(repoId);
    }

    @PostMapping("/{repoId}/search")
    public CodeSearchResponse searchRepo(@PathVariable String repoId,
                                         @RequestBody CodeSearchRequest request) {
        return codeSearchService.search(repoId, request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(EntityNotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalState(IllegalStateException e) {
        return Map.of("message", e.getMessage());
    }
}
