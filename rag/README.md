# Document Search & RAG

A self-contained Streamlit web app for answering questions over a collection of
daily JSON documents. Embeddings are produced via the Anthropic API, retrieval
uses cosine similarity, and answers are generated with Claude Opus 4.7 using the
top-5 retrieved documents as context.

## What it does

1. Accept uploaded JSON documents (each with `id`, `date`, `content`)
2. Generate a 384-dim embedding per document via the Anthropic API
3. Embed the user's question the same way
4. Score every document by cosine similarity and keep the top 5
5. Build a source-labeled prompt and call Claude Opus 4.7 for the final answer
6. Display the answer alongside the sources (id, date, similarity score, text)

## Run it

```bash
cd rag
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
streamlit run app.py
```

The app opens in your browser. Upload `sample_docs.json` (or your own file) in
the sidebar, click **Embed documents**, then ask a question.

### Input format

A JSON list of documents, or `{"documents": [...]}`:

```json
[
  {"id": "doc-001", "date": "2026-04-15", "content": "..."},
  {"id": "doc-002", "date": "2026-04-16", "content": "..."}
]
```

## How embeddings work

The Anthropic API does not have a dedicated embeddings endpoint, so the app
uses Claude Haiku 4.5 to extract a comma-separated list of semantic features
(topics, entities, actions, sentiments) from each document, then hashes those
features deterministically into a 384-dimensional vector. The same function
embeds documents at index time and questions at query time, so they live in the
same space and cosine similarity works as expected.

Trade-off: one API call per document at index time. For large collections you'd
swap this for a dedicated embedding provider and keep the rest of the pipeline
unchanged.

## Files

- `app.py` — Streamlit UI
- `rag_pipeline.py` — embedding, retrieval, and answer generation
- `sample_docs.json` — eight example daily docs to try the app with
- `requirements.txt` — Python dependencies
