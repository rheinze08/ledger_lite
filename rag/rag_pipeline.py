"""RAG pipeline: embedding, retrieval, and answer generation via the Anthropic API."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Iterable

import numpy as np
from anthropic import Anthropic

EMBEDDING_DIM = 384
EMBED_MODEL = "claude-haiku-4-5"
GENERATION_MODEL = "claude-opus-4-7"

FEATURE_EXTRACTION_PROMPT = (
    "Extract a comma-separated list of 25-40 concise semantic features from the text "
    "below. Include: key topics, named entities, actions, sentiments, and distinctive "
    "phrases. Output only the comma-separated list, no commentary, no numbering.\n\n"
    "Text:\n{text}"
)


@dataclass
class Document:
    id: str
    date: str
    content: str
    embedding: list[float]


@dataclass
class RetrievedDocument:
    document: Document
    similarity: float


def _hash_tokens_to_vector(tokens: Iterable[str], dim: int = EMBEDDING_DIM) -> np.ndarray:
    """Project tokens into a deterministic `dim`-dimensional vector via feature hashing."""
    vec = np.zeros(dim, dtype=np.float64)
    for token in tokens:
        token = token.strip().lower()
        if not token:
            continue
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dim
        sign = 1.0 if digest[4] & 1 else -1.0
        vec[index] += sign
    norm = np.linalg.norm(vec)
    if norm > 0:
        vec /= norm
    return vec


def _tokenize_features(features_text: str) -> list[str]:
    parts = re.split(r"[,\n;]+", features_text)
    return [p.strip() for p in parts if p.strip()]


def generate_embedding(client: Anthropic, text: str) -> list[float]:
    """Generate a 384-dim embedding by extracting features via the Anthropic API, then hashing."""
    response = client.messages.create(
        model=EMBED_MODEL,
        max_tokens=512,
        messages=[
            {
                "role": "user",
                "content": FEATURE_EXTRACTION_PROMPT.format(text=text[:8000]),
            }
        ],
    )
    features_text = next(
        (block.text for block in response.content if block.type == "text"),
        "",
    )
    feature_tokens = _tokenize_features(features_text)
    return _hash_tokens_to_vector(feature_tokens).tolist()


def cosine_similarity(a: list[float] | np.ndarray, b: list[float] | np.ndarray) -> float:
    va = np.asarray(a, dtype=np.float64)
    vb = np.asarray(b, dtype=np.float64)
    denom = np.linalg.norm(va) * np.linalg.norm(vb)
    if denom == 0:
        return 0.0
    return float(np.dot(va, vb) / denom)


def top_k_similar(
    query_embedding: list[float],
    documents: list[Document],
    k: int = 5,
) -> list[RetrievedDocument]:
    scored = [
        RetrievedDocument(document=doc, similarity=cosine_similarity(query_embedding, doc.embedding))
        for doc in documents
    ]
    scored.sort(key=lambda r: r.similarity, reverse=True)
    return scored[:k]


def build_answer_prompt(question: str, sources: list[RetrievedDocument]) -> str:
    blocks = []
    for i, hit in enumerate(sources, start=1):
        blocks.append(
            f"[SOURCE {i}] id={hit.document.id} date={hit.document.date}\n"
            f"{hit.document.content}"
        )
    context = "\n\n---\n\n".join(blocks)
    return (
        "You are answering a question using the document excerpts below.\n"
        "- Cite sources inline as [SOURCE 1], [SOURCE 2], etc. matching the labels.\n"
        "- If the sources do not contain enough information, say so directly.\n"
        "- Do not invent facts that are not supported by the sources.\n\n"
        f"Sources:\n{context}\n\n"
        f"Question: {question}\n\n"
        "Answer:"
    )


def generate_answer(
    client: Anthropic,
    question: str,
    sources: list[RetrievedDocument],
) -> str:
    prompt = build_answer_prompt(question, sources)
    response = client.messages.create(
        model=GENERATION_MODEL,
        max_tokens=2048,
        thinking={"type": "adaptive"},
        messages=[{"role": "user", "content": prompt}],
    )
    return "\n".join(block.text for block in response.content if block.type == "text").strip()


def parse_documents_json(raw: str | bytes) -> list[dict]:
    """Parse uploaded JSON. Accepts a list of documents or {"documents": [...]}."""
    data = json.loads(raw)
    if isinstance(data, dict) and "documents" in data:
        data = data["documents"]
    if not isinstance(data, list):
        raise ValueError("JSON must be a list of documents or an object with a 'documents' list.")
    for doc in data:
        missing = {"id", "date", "content"} - set(doc)
        if missing:
            raise ValueError(f"Document missing required fields: {sorted(missing)}")
    return data


def embed_documents(client: Anthropic, raw_docs: list[dict]) -> list[Document]:
    documents: list[Document] = []
    for doc in raw_docs:
        embedding = generate_embedding(client, doc["content"])
        documents.append(
            Document(
                id=str(doc["id"]),
                date=str(doc["date"]),
                content=str(doc["content"]),
                embedding=embedding,
            )
        )
    return documents
