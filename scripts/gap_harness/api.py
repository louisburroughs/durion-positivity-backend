"""I/O adapters for the live loop: the MCP chat API and the judge LLM.

Both are thin stdlib (`urllib`) clients so the harness has no third-party runtime dependency beyond
``pg8000`` (retrieval only). They are injected into the orchestrator, so tests drive it with fakes.

Auth: the chat API is bearer-gated (`mcp:chat:execute`). Minting JWTs is the security service's job,
so the harness takes tokens as input — a per-role token map (env ``POS_MCP_TOKEN_<ROLE>`` or a JSON
file) or a single token — rather than minting them itself. Asking a question "as an actor" means
selecting that actor's token so retrieval scope + permission gating match production (§3.1).
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Callable, Optional

from .model import Actor

# A token provider maps an actor to the bearer token to send. Returns None if no token is known.
TokenProvider = Callable[[Actor], Optional[str]]


def static_token_provider(token: str) -> TokenProvider:
    return lambda _actor: token


def role_token_provider(role_to_token: dict[str, str]) -> TokenProvider:
    """Select a token by the actor's role (e.g. from POS_MCP_TOKEN_ROLE_ADMIN env vars)."""
    return lambda actor: role_to_token.get(actor.role)


def env_role_token_provider(prefix: str = "POS_MCP_TOKEN_") -> TokenProvider:
    mapping = {}
    for key, val in os.environ.items():
        if key.startswith(prefix) and val:
            mapping[key[len(prefix):]] = val
    return role_token_provider(mapping)


class ChatClient:
    """POSTs to ``/v1/mcp/chat`` and returns the answer text (McpChatController.ChatResponse)."""

    def __init__(self, base_url: str, token_provider: TokenProvider, timeout: int = 120):
        self._base = base_url.rstrip("/")
        self._token = token_provider
        self._timeout = timeout

    def ask(self, actor: Actor, message: str) -> str:
        token = self._token(actor)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(
            f"{self._base}/v1/mcp/chat",
            data=json.dumps({"message": message}).encode(),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            body = json.loads(resp.read())
        return body.get("response", "")


# --- judge LLM clients -------------------------------------------------------------------------
def ollama_judge(base_url: str, model: str, timeout: int = 120) -> Callable[[str], str]:
    """A judge backed by an Ollama chat model (`/api/chat`). Temperature 0 for determinism (§11)."""
    base = base_url.rstrip("/")

    def _llm(prompt: str) -> str:
        req = urllib.request.Request(
            f"{base}/api/chat",
            data=json.dumps(
                {
                    "model": model,
                    "messages": [{"role": "user", "content": prompt}],
                    "stream": False,
                    "options": {"temperature": 0},
                }
            ).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read())
        return body.get("message", {}).get("content", "")

    return _llm


def openai_compatible_judge(
    base_url: str, model: str, api_key: Optional[str] = None, timeout: int = 120
) -> Callable[[str], str]:
    """A judge backed by any OpenAI-compatible `/chat/completions` endpoint (temperature 0)."""
    base = base_url.rstrip("/")

    def _llm(prompt: str) -> str:
        headers = {"Content-Type": "application/json"}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        req = urllib.request.Request(
            f"{base}/chat/completions",
            data=json.dumps(
                {"model": model, "messages": [{"role": "user", "content": prompt}], "temperature": 0}
            ).encode(),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read())
        return body["choices"][0]["message"]["content"]

    return _llm
