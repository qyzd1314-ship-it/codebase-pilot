package com.yupi.codebasepilot.task.controller;

import com.yupi.codebasepilot.task.dto.AgentArtifactDto;
import com.yupi.codebasepilot.task.service.AgentArtifactService.ArtifactFilePayload;
import com.yupi.codebasepilot.task.service.AgentArtifactService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/agent/tasks")
public class AgentArtifactController {

    private final AgentArtifactService agentArtifactService;

    public AgentArtifactController(AgentArtifactService agentArtifactService) {
        this.agentArtifactService = agentArtifactService;
    }

    @GetMapping("/{taskId}/artifacts")
    public List<AgentArtifactDto> listArtifacts(@PathVariable Long taskId) {
        return agentArtifactService.listArtifacts(taskId);
    }

    @GetMapping("/{taskId}/artifacts/{artifactId}/content")
    public ResponseEntity<String> previewArtifact(@PathVariable Long taskId, @PathVariable Long artifactId) {
        ArtifactFilePayload payload = agentArtifactService.getArtifactFile(taskId, artifactId);
        String content = agentArtifactService.readArtifactContent(taskId, artifactId);
        MediaType mediaType = MediaType.TEXT_PLAIN;
        if ("text/markdown".equalsIgnoreCase(payload.artifact().getContentType())) {
            mediaType = MediaType.parseMediaType("text/markdown");
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(content);
    }

    @GetMapping("/{taskId}/artifacts/{artifactId}/download")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable Long taskId, @PathVariable Long artifactId) {
        ArtifactFilePayload payload = agentArtifactService.getArtifactFile(taskId, artifactId);
        FileSystemResource resource = new FileSystemResource(payload.file());
        String encodedFilename = URLEncoder.encode(payload.artifact().getArtifactName(), StandardCharsets.UTF_8);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (payload.artifact().getContentType() != null && !payload.artifact().getContentType().isBlank()) {
            mediaType = MediaType.parseMediaType(payload.artifact().getContentType());
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(encodedFilename, StandardCharsets.UTF_8).build().toString())
                .contentLength(payload.file().length())
                .body(resource);
    }
}
