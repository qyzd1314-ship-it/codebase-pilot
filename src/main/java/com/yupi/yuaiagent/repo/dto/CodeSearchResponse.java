package com.yupi.yuaiagent.repo.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class CodeSearchResponse {

    List<CodeSearchResultDto> results;
}
