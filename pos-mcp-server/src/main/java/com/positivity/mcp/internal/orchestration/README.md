| Area | `SessionAgentManager` | `StreamingSessionAgentManager` |
|---|---|---|
| Purpose | Standard request/response chat manager | Token-streaming chat manager |
| Service interface | [`AgentOrchestrationService`]( /home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/service/AgentOrchestrationService.java:9) | [`StreamingAgentOrchestrationService`]( /home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/service/StreamingAgentOrchestrationService.java:6) |
| Main method | `chat(userId, role, message)` | `streamChat(userId, role, message)` |
| Return type | `String` | `Flux<String>` |
| Model type | `ChatModel` ([SessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java:39)) | `StreamingChatModel` ([StreamingSessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java:39)) |
| Assistant proxy | [`PosAssistant`]( /home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/PosAssistant.java:7) | [`StreamingPosAssistant`]( /home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingPosAssistant.java:8) |
| Assistant output | Final full response | `TokenStream` partial responses |
| Controller endpoint | `POST /v1/mcp/chat` ([McpChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpChatController.java:37)) | `POST /v1/mcp/chat/stream` ([McpStreamingChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpStreamingChatController.java:35)) |
| HTTP response shape | JSON body with `response` | SSE `text/event-stream` events |
| Tool wiring | Role tools + always adds `ExaWebSearchTool`, `InventoryFacadeTool`, `OrderFacadeTool` ([SessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java:149)) | Role tools + always adds only `ExaWebSearchTool` ([StreamingSessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java:145)) |
| Audit timing | Audits after full response returns | Audits on stream completion / error |
| Streaming mechanics | None | Wraps LangChain `TokenStream` into Reactor `Flux` via `Flux.create(...)` and `onPartialResponse(...)` ([StreamingSessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java:102)) |
| Shared behavior | Per-user cache, role-aware agent reuse, RAG retriever, rate limiting, eviction | Same |

The most important practical difference is this: `SessionAgentManager` is for “wait, then get the whole answer,” while `StreamingSessionAgentManager` is for “start sending tokens immediately as they’re generated.”

The most important implementation difference is the tool set mismatch. The non-streaming path guarantees `InventoryFacadeTool` and `OrderFacadeTool`, but the streaming path currently does not. If that was not intentional, it’s a likely parity bug.
