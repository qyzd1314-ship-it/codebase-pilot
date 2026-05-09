package com.yupi.yuaiagent.task.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.task.entity.AgentTask;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class AgentTaskWorkspaceService {

    public String initializeWorkspace(AgentTask task) {
        String workspacePath = buildWorkspacePath(task);
        File workspaceDir = FileUtil.mkdir(workspacePath);
        FileUtil.mkdir(new File(workspaceDir, "artifacts"));
        FileUtil.mkdir(new File(workspaceDir, "downloads"));
        task.setWorkspacePath(workspaceDir.getAbsolutePath());
        return workspaceDir.getAbsolutePath();
    }

    public File getWorkspaceDir(AgentTask task) {
        String workspacePath = StrUtil.blankToDefault(task.getWorkspacePath(), buildWorkspacePath(task));
        return FileUtil.mkdir(workspacePath);
    }

    public File resolveWorkspaceFile(AgentTask task, String relativePath) {
        return resolveWithinWorkspace(getWorkspaceDir(task), relativePath);
    }

    public File createSessionWorkspace(String workspaceKey) {
        String safeKey = StrUtil.blankToDefault(workspaceKey, "default").replaceAll("[^a-zA-Z0-9_-]", "-");
        return FileUtil.mkdir(FileConstant.FILE_SAVE_DIR + "/tool-runs/" + safeKey);
    }

    public File resolveSessionFile(String workspaceKey, String relativePath) {
        return resolveWithinWorkspace(createSessionWorkspace(workspaceKey), relativePath);
    }

    private String buildWorkspacePath(AgentTask task) {
        return FileConstant.FILE_SAVE_DIR + "/tasks/" + task.getTaskNo();
    }

    private File resolveWithinWorkspace(File workspaceDir, String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            throw new IllegalArgumentException("Workspace file path is required.");
        }
        String normalizedPath = relativePath.replace("\\", "/");
        if (normalizedPath.contains("..")) {
            throw new IllegalArgumentException("Parent path traversal is not allowed: " + relativePath);
        }
        File normalizedWorkspaceDir = FileUtil.mkdir(workspaceDir);
        File targetFile = new File(normalizedWorkspaceDir, normalizedPath);
        String workspaceCanonicalPath = normalizedWorkspaceDir.getAbsolutePath();
        String targetCanonicalPath = targetFile.getAbsolutePath();
        if (!targetCanonicalPath.startsWith(workspaceCanonicalPath)) {
            throw new IllegalArgumentException("Resolved path is outside the workspace: " + relativePath);
        }
        File parentFile = targetFile.getParentFile();
        if (parentFile != null) {
            FileUtil.mkdir(parentFile);
        }
        return targetFile;
    }
}
