-- pos-mcp-server #2075: per-turn feedback and the profile-independent turn summary (user decision U3).
--
-- Turn summary (answer_path, answer_source, tools_called, latency_ms): written once by the chat path on
-- the assistant row of a CHAT turn, in every environment, so a rating joins to how the answer was
-- produced without the alpha-only eval trace. NULL on user rows, CLIENT rows and pre-V4 rows.
-- tools_called is a JSON array of tool names in call order (duplicates kept, at most 64).
--
-- Feedback (feedback_*): the owner's single rating of a chat-path (origin CHAT) assistant message; a
-- client-appended (CLIENT) answer cannot be rated (decision O4). POST replaces it by UPDATE,
-- DELETE nulls it. No new table: only the conversation owner can reach a message, so "one rating per
-- (subject, message)" is one rating per message. Conversation delete and retention purge cascade.
--
-- mcp_eval_turn_trace.message_id: the assistant message a traced turn produced (alpha profile only).
-- Deliberately no foreign key: the trace is written before the message row exists, the message may
-- never be persisted (conversation deleted mid-turn), and the two retentions differ (24 h vs 30 days).
-- Joins must include tenant_id.

ALTER TABLE public.mcp_message
    ADD COLUMN answer_path character varying(16),
    ADD COLUMN answer_source character varying(32),
    ADD COLUMN tools_called jsonb,
    ADD COLUMN latency_ms integer,
    ADD COLUMN feedback_rating character varying(20),
    ADD COLUMN feedback_reason character varying(20),
    ADD COLUMN feedback_comment character varying(1000),
    ADD COLUMN feedback_at timestamp with time zone;

ALTER TABLE public.mcp_message
    ADD CONSTRAINT mcp_message_answer_path_check CHECK (answer_path IN ('AGENT', 'SIMPLE_CHAT')),
    ADD CONSTRAINT mcp_message_latency_check CHECK (latency_ms >= 0),
    ADD CONSTRAINT mcp_message_tools_called_check
        CHECK (tools_called IS NULL OR jsonb_typeof(tools_called) = 'array'),
    ADD CONSTRAINT mcp_message_turn_summary_scope_check CHECK (
        (answer_path IS NULL AND answer_source IS NULL AND tools_called IS NULL AND latency_ms IS NULL)
        OR (role = 'assistant' AND origin = 'CHAT')),
    ADD CONSTRAINT mcp_message_feedback_rating_check CHECK (feedback_rating IN ('helpful', 'not_helpful')),
    ADD CONSTRAINT mcp_message_feedback_reason_check
        CHECK (feedback_reason IN ('incorrect', 'incomplete', 'not_relevant', 'other')),
    ADD CONSTRAINT mcp_message_feedback_scope_check CHECK (
        (feedback_rating IS NULL AND feedback_reason IS NULL AND feedback_comment IS NULL AND feedback_at IS NULL)
        OR (feedback_rating IS NOT NULL AND feedback_at IS NOT NULL AND role = 'assistant' AND origin = 'CHAT'));

-- Grading scan: rated turns per tenant, newest first.
CREATE INDEX idx_mcp_message_rated
    ON public.mcp_message USING btree (tenant_id, feedback_at DESC)
    WHERE feedback_rating IS NOT NULL;

ALTER TABLE public.mcp_eval_turn_trace ADD COLUMN message_id uuid;

CREATE INDEX idx_mcp_eval_turn_trace_message
    ON public.mcp_eval_turn_trace USING btree (tenant_id, message_id)
    WHERE message_id IS NOT NULL;
