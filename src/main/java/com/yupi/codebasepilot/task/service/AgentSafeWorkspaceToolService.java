package com.yupi.codebasepilot.task.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AgentSafeWorkspaceToolService {

    private static final List<String> ALLOWED_TERMINAL_COMMANDS = List.of("dir", "echo", "type");

    public String readFile(File workspaceDir, String relativePath) {
        File targetFile = resolveWorkspaceFile(workspaceDir, relativePath);
        if (!targetFile.exists()) {
            return "File does not exist: " + targetFile.getAbsolutePath();
        }
        return FileUtil.readUtf8String(targetFile);
    }

    public String writeFile(File workspaceDir, String relativePath, String content) {
        File targetFile = resolveWorkspaceFile(workspaceDir, relativePath);
        FileUtil.writeUtf8String(content, targetFile);
        return "File written successfully to: " + targetFile.getAbsolutePath();
    }

    public String downloadFile(File workspaceDir, String relativePath, String url) {
        validateUrl(url);
        File targetFile = resolveWorkspaceFile(workspaceDir, relativePath);
        HttpUtil.downloadFile(url, targetFile);
        return "Resource downloaded successfully to: " + targetFile.getAbsolutePath();
    }

    public String executeTerminalCommand(File workspaceDir, String command) throws IOException, InterruptedException {
        validateTerminalCommand(command);
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
        builder.directory(workspaceDir);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed with exit code " + exitCode + ".");
        }
        return output.toString().trim();
    }

    public String generatePdf(File workspaceDir, String relativePath, String content) {
        File targetFile = resolveWorkspaceFile(workspaceDir, relativePath);
        try (PdfWriter writer = new PdfWriter(targetFile);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
            document.setFont(font);
            document.add(new Paragraph(content));
            return "PDF generated successfully to: " + targetFile.getAbsolutePath();
        } catch (IOException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    public File resolveWorkspaceFile(File workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
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

    private void validateTerminalCommand(String command) {
        if (!ALLOWED_TERMINAL_COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Terminal command is not permitted: " + command);
        }
    }

    private void validateUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("Only http/https URLs are allowed.");
        }
    }
}
