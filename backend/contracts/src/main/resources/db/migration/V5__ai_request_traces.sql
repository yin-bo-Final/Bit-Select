CREATE TABLE ai_request_trace (
  id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  question_preview VARCHAR(240) NOT NULL,
  status VARCHAR(16) NOT NULL,
  started_at TIMESTAMP(3) NOT NULL,
  finished_at TIMESTAMP(3) NULL,
  last_activity_at TIMESTAMP(3) NOT NULL,
  first_token_ms BIGINT NULL,
  duration_ms BIGINT NULL,
  output_chars BIGINT NOT NULL DEFAULT 0,
  error_code VARCHAR(32) NULL,
  CONSTRAINT ck_ai_trace_status CHECK (status IN ('running','completed','failed','cancelled'))
);
CREATE INDEX idx_ai_trace_started ON ai_request_trace(started_at,id);
CREATE INDEX idx_ai_trace_status ON ai_request_trace(status,started_at,id);
CREATE INDEX idx_ai_trace_user ON ai_request_trace(user_id,started_at,id);
CREATE TABLE ai_request_phase (
  request_id VARCHAR(36) NOT NULL,
  phase_code VARCHAR(24) NOT NULL,
  sequence_no INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  started_at TIMESTAMP(3) NOT NULL,
  finished_at TIMESTAMP(3) NULL,
  duration_ms BIGINT NULL,
  PRIMARY KEY(request_id,phase_code),
  CONSTRAINT fk_ai_phase_trace FOREIGN KEY(request_id) REFERENCES ai_request_trace(id) ON DELETE CASCADE,
  CONSTRAINT ck_ai_phase_status CHECK (status IN ('running','completed','failed','cancelled'))
);
