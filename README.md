# MiniRAG

[![Build](https://github.com/KiMelody/minirag/actions/workflows/docker.yml/badge.svg)](https://github.com/KiMelody/minirag/actions/workflows/docker.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-blue)
![RSS](https://img.shields.io/badge/RSS-~250MB-brightgreen)

A self-hosted RAG knowledge base in a single Spring Boot process: SQLite storage, hybrid retrieval, cited answers, and a built-in MCP server. No vector database, no message queue.

[Live demo](https://minirag.kimelody.de5.net/) (ask the owner for the access code) · [Evaluation report](eval/eval-report.md)

## Features

- **Hybrid retrieval** — in-memory cosine + SQLite FTS5 (BM25), fused with RRF; optional LLM rerank
- **Cited answers** — clickable `[n]` citations; only sources actually cited are displayed
- **Streaming web UI** — SSE, typewriter rendering, markdown, retrieval-mode switcher (vector / hybrid / rerank)
- **Small footprint** — single process, ~250MB RSS, runs on a 1GB VPS
- **Multiple interfaces** — MCP server (SSE transport), REST API, web UI, WeChat Work callback integration

## Quick start

```bash
docker run -d --name minirag -p 18090:18090 --memory 512m \
  -v $(pwd)/data:/app/data \
  -e DEEPSEEK_API_KEY=sk-... \
  -e EMBEDDING_API_KEY=sk-... \
  ghcr.io/kimelody/minirag:latest
```

Open http://localhost:18090 and start asking. Data lives in `./data` (SQLite, created automatically).

## Configuration

| Env | Required | Default | Description |
|---|---|---|---|
| `DEEPSEEK_BASE_URL` | no | `https://api.deepseek.com` | Any OpenAI-compatible chat endpoint |
| `DEEPSEEK_API_KEY` | yes | — | Chat model API key |
| `EMBEDDING_BASE_URL` | no | `https://api.siliconflow.cn/v1` | OpenAI-compatible embeddings endpoint |
| `EMBEDDING_API_KEY` | yes | — | Embeddings API key |
| `EMBEDDING_MODEL` | no | `BAAI/bge-m3` | Embedding model |
| `DEMO_KEY` | no | — | Visitor access code for read APIs; unset = open (local dev) |
| `ADMIN_KEY` | no | — | Admin key required for ingest APIs |

## MCP

Listed on the official MCP Registry as `io.github.KiMelody/minirag`. Two tools: `query(question, mode?)` — ask and get a cited answer; `ingest_text(docId, text, adminKey)` — add a document.

```bash
claude mcp add --transport sse minirag http://localhost:18090/sse
```

```json
{
  "mcpServers": {
    "minirag": {
      "url": "http://localhost:18090/sse"
    }
  }
}
```

The hosted demo endpoint requires basic auth: `https://minirag.kimelody.de5.net/sse` (ask the owner for credentials).

## REST API

| Method | Path | Description |
|---|---|---|
| POST | `/api/chat` | Ask; returns answer + cited sources (JSON) |
| POST | `/api/chat/stream` | Same, streamed as SSE (`meta` / `token` / `sources` / `done`) |
| GET | `/api/search?q=&mode=` | Retrieval debug: `vector` \| `hybrid` \| `rerank` |
| POST | `/api/documents/text` | Ingest `{docId, text}` (requires `X-Admin-Key`) |
| POST | `/api/documents` | Ingest an uploaded file, multipart (requires `X-Admin-Key`) |

## Evaluation

An eval harness ships in [eval/](eval/): labeled question sets, Top-1 / Recall / MRR scoring over three retrieval modes — hybrid+rerank reaches Top-1 100% / MRR 1.0 on the demo set ([report](eval/eval-report.md)); a 20-question labor-law evaluation with corpus-cleaning scripts lives in [eval/law/](eval/law/eval-report.md). Bring your own corpus; raw source texts are not redistributed.

## Build from source

```bash
mvn package
java -jar target/minirag-0.1.0-SNAPSHOT.jar
```

Requires Java 21. API keys can be put in a `.env` file in the working directory (git-ignored).

## 中文说明

单进程私有化 RAG 知识库：SQLite 存储 + 混合检索（向量 + BM25，RRF 融合，可选 LLM 重排），回答带可点击引用角标，自带网页演示、REST API 与 MCP server（SSE），内存占用约 250MB，1GB 小服务器可跑。[在线演示](https://minirag.kimelody.de5.net/)（访问码联系作者）。

## License

[MIT](LICENSE)
