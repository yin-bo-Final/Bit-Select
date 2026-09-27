CREATE INDEX idx_message_event_order ON ai_message(user_id,turn_id,id);
CREATE INDEX idx_memory_projection ON ai_memory(projected,updated_at);
