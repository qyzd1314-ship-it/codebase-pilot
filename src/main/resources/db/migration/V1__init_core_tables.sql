CREATE TABLE IF NOT EXISTS repo (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(1024) NOT NULL,
    branch VARCHAR(128) NOT NULL,
    local_path VARCHAR(1024) NOT NULL,
    indexed_status VARCHAR(32) NOT NULL,
    file_count INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    last_indexed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_indexed_status ON repo (indexed_status);
CREATE INDEX IF NOT EXISTS idx_repo_last_indexed_at ON repo (last_indexed_at);

CREATE TABLE IF NOT EXISTS code_chunk (
    id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    language VARCHAR(64),
    symbol_name VARCHAR(255),
    chunk_type VARCHAR(32) NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    token_count INTEGER,
    embedding_json TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_code_chunk_repo_id ON code_chunk (repo_id);
CREATE INDEX IF NOT EXISTS idx_code_chunk_file_path ON code_chunk (file_path);
CREATE INDEX IF NOT EXISTS idx_code_chunk_repo_symbol ON code_chunk (repo_id, symbol_name);

CREATE TABLE IF NOT EXISTS agent_task (
    id BIGSERIAL PRIMARY KEY,
    task_no VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    goal TEXT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    repo_id VARCHAR(64),
    business_type VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(128),
    workspace_path VARCHAR(512),
    source_task_id BIGINT,
    source_task_no VARCHAR(64),
    source_task_title VARCHAR(255),
    source_task_relation VARCHAR(32),
    inherited_context_summary TEXT,
    current_step_seq INTEGER,
    plan_summary TEXT,
    previous_plan_summary TEXT,
    previous_plan_steps_snapshot TEXT,
    plan_diff_summary TEXT,
    plan_diff_snapshot TEXT,
    final_result TEXT,
    handoff_context_summary TEXT,
    task_summary TEXT,
    delivery_status VARCHAR(64),
    artifact_count INTEGER,
    deliverable_artifact_count INTEGER,
    review_summary TEXT,
    review_suggested_action VARCHAR(64),
    review_suggested_step_seq INTEGER,
    error_message TEXT,
    auto_approve_low_risk BOOLEAN NOT NULL,
    current_round INTEGER NOT NULL,
    max_round INTEGER NOT NULL,
    replan_count INTEGER NOT NULL,
    consecutive_same_reason_replan_count INTEGER NOT NULL,
    max_consecutive_same_reason_replan_count INTEGER NOT NULL,
    last_replan_reason TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_task_repo_id ON agent_task (repo_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_status ON agent_task (status);
CREATE INDEX IF NOT EXISTS idx_agent_task_repo_status ON agent_task (repo_id, status);

CREATE TABLE IF NOT EXISTS agent_task_step (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_seq INTEGER NOT NULL,
    step_title VARCHAR(255) NOT NULL,
    step_type VARCHAR(64) NOT NULL,
    assigned_agent VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_category VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    planner_output TEXT,
    executor_input TEXT,
    executor_output TEXT,
    evidence_refs TEXT,
    retry_count INTEGER NOT NULL,
    max_retry INTEGER NOT NULL,
    requires_approval BOOLEAN NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_task_step_task_id ON agent_task_step (task_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_step_task_seq ON agent_task_step (task_id, step_seq);
CREATE INDEX IF NOT EXISTS idx_agent_task_step_status ON agent_task_step (status);

CREATE TABLE IF NOT EXISTS agent_artifact (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_name VARCHAR(255) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    content_type VARCHAR(128),
    description TEXT,
    structured_content TEXT,
    metadata TEXT,
    evidence_refs TEXT,
    size_bytes BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_artifact_task_id ON agent_artifact (task_id);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_artifact_type ON agent_artifact (artifact_type);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_task_type ON agent_artifact (task_id, artifact_type);

CREATE TABLE IF NOT EXISTS agent_task_event (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    event_level VARCHAR(32) NOT NULL,
    event_content TEXT NOT NULL,
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_task_event_task_id ON agent_task_event (task_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_event_step_id ON agent_task_event (step_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_event_task_step ON agent_task_event (task_id, step_id);

CREATE TABLE IF NOT EXISTS agent_tool_call (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_category VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    request_payload TEXT,
    response_payload TEXT,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_call_task_id ON agent_tool_call (task_id);
CREATE INDEX IF NOT EXISTS idx_agent_tool_call_step_id ON agent_tool_call (step_id);
CREATE INDEX IF NOT EXISTS idx_agent_tool_call_task_step ON agent_tool_call (task_id, step_id);

CREATE TABLE IF NOT EXISTS agent_approval (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT,
    approval_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    reason TEXT,
    payload TEXT,
    status VARCHAR(32) NOT NULL,
    decision_by VARCHAR(64),
    decision_note TEXT,
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_approval_task_id ON agent_approval (task_id);
CREATE INDEX IF NOT EXISTS idx_agent_approval_step_id ON agent_approval (step_id);
CREATE INDEX IF NOT EXISTS idx_agent_approval_status ON agent_approval (status);

CREATE TABLE IF NOT EXISTS manus_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(128),
    tags VARCHAR(512),
    workspace_path VARCHAR(512),
    message_snapshot TEXT,
    status VARCHAR(32),
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS manus_session_approval (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason TEXT,
    approved_by VARCHAR(128),
    decision_note TEXT,
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS manus_session_event (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    title VARCHAR(255),
    content TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS manus_session_tool_call (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_category VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    request_payload TEXT,
    response_payload TEXT,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
