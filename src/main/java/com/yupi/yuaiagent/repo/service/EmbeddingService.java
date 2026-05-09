package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.repo.entity.CodeChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmbeddingService {

    private static final int EMBEDDING_DIMENSION = 128;

    private final ObjectMapper objectMapper;

    public EmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generateChunkEmbeddingJson(CodeChunk chunk) {
        return toJson(embed(buildChunkSourceText(chunk)));
    }

    public List<Double> embedQuery(String query) {
        return embed(query);
    }

    public List<Double> readEmbedding(String embeddingJson) {
        if (StrUtil.isBlank(embeddingJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(embeddingJson, new TypeReference<List<Double>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse embedding json.", e);
        }
    }

    public double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.size(); i++) {
            double leftValue = left.get(i);
            double rightValue = right.get(i);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public List<Double> embedFallbackChunk(CodeChunk chunk) {
        return embed(buildChunkSourceText(chunk));
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize embedding.", e);
        }
    }

    private String buildChunkSourceText(CodeChunk chunk) {
        return String.join("\n",
                StrUtil.nullToEmpty(chunk.getFilePath()),
                StrUtil.nullToEmpty(chunk.getSymbolName()),
                StrUtil.nullToEmpty(chunk.getLanguage()),
                StrUtil.nullToEmpty(chunk.getContent()));
    }

    private List<Double> embed(String text) {
        double[] vector = new double[EMBEDDING_DIMENSION];
        Matcher matcher = Pattern.compile("[A-Za-z0-9_./\\\\-]+|[\\p{IsHan}]{1,}").matcher(StrUtil.nullToEmpty(text));
        while (matcher.find()) {
            String token = matcher.group().trim().toLowerCase(Locale.ROOT);
            if (token.length() < 2) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), EMBEDDING_DIMENSION);
            vector[index] += 1D;
        }
        normalize(vector);
        List<Double> result = new ArrayList<>(EMBEDDING_DIMENSION);
        for (double value : vector) {
            result.add(value);
        }
        return result;
    }

    private void normalize(double[] vector) {
        double norm = 0D;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0D) {
            return;
        }
        double denominator = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / denominator;
        }
    }
}
