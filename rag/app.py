"""Streamlit front-end for the document search & RAG pipeline."""

from __future__ import annotations

import os

import streamlit as st
from anthropic import Anthropic, APIError

from rag_pipeline import (
    EMBEDDING_DIM,
    GENERATION_MODEL,
    embed_documents,
    generate_answer,
    generate_embedding,
    parse_documents_json,
    top_k_similar,
)

st.set_page_config(page_title="Document Search & RAG", layout="wide")
st.title("Document Search & RAG")
st.caption(
    f"Embeddings: {EMBEDDING_DIM} dims via Anthropic API feature extraction. "
    f"Answers: {GENERATION_MODEL} with adaptive thinking."
)

if "documents" not in st.session_state:
    st.session_state.documents = []
if "last_sources" not in st.session_state:
    st.session_state.last_sources = []
if "last_answer" not in st.session_state:
    st.session_state.last_answer = ""


def get_client() -> Anthropic | None:
    api_key = os.environ.get("ANTHROPIC_API_KEY") or st.session_state.get("api_key", "")
    if not api_key:
        return None
    return Anthropic(api_key=api_key)


with st.sidebar:
    st.header("Setup")
    if not os.environ.get("ANTHROPIC_API_KEY"):
        st.session_state.api_key = st.text_input(
            "Anthropic API key",
            type="password",
            value=st.session_state.get("api_key", ""),
            help="Or set ANTHROPIC_API_KEY in your environment.",
        )
    else:
        st.success("Using ANTHROPIC_API_KEY from environment.")

    st.divider()
    st.header("Upload daily docs (JSON)")
    st.caption("Each document needs: id, date, content.")
    uploaded = st.file_uploader("Documents file", type=["json"])

    if uploaded is not None and st.button("Embed documents", type="primary"):
        client = get_client()
        if client is None:
            st.error("Provide an Anthropic API key first.")
        else:
            try:
                raw_docs = parse_documents_json(uploaded.read())
            except (ValueError, Exception) as exc:
                st.error(f"Could not parse JSON: {exc}")
            else:
                progress = st.progress(0.0, text="Embedding documents...")
                status = st.empty()
                try:
                    embedded = []
                    for i, doc in enumerate(raw_docs, start=1):
                        status.text(f"Embedding {i}/{len(raw_docs)}: {doc['id']}")
                        embedded.extend(embed_documents(client, [doc]))
                        progress.progress(i / len(raw_docs))
                    st.session_state.documents = embedded
                    st.session_state.last_sources = []
                    st.session_state.last_answer = ""
                    progress.empty()
                    status.empty()
                    st.success(f"Embedded {len(embedded)} documents.")
                except APIError as exc:
                    progress.empty()
                    status.empty()
                    st.error(f"Anthropic API error while embedding: {exc}")

    if st.session_state.documents:
        st.divider()
        st.metric("Documents indexed", len(st.session_state.documents))
        if st.button("Clear index"):
            st.session_state.documents = []
            st.session_state.last_sources = []
            st.session_state.last_answer = ""
            st.rerun()


st.header("Ask a question")
question = st.text_input("Your question", placeholder="What were the key decisions last week?")
top_k = st.slider("Top-K sources", min_value=1, max_value=10, value=5)

if st.button("Search & answer", disabled=not question):
    client = get_client()
    if client is None:
        st.error("Provide an Anthropic API key first.")
    elif not st.session_state.documents:
        st.error("Upload and embed documents before asking a question.")
    else:
        try:
            with st.spinner("Embedding question..."):
                query_embedding = generate_embedding(client, question)
            with st.spinner("Retrieving top sources..."):
                hits = top_k_similar(query_embedding, st.session_state.documents, k=top_k)
            with st.spinner("Generating answer..."):
                answer = generate_answer(client, question, hits)
            st.session_state.last_sources = hits
            st.session_state.last_answer = answer
        except APIError as exc:
            st.error(f"Anthropic API error: {exc}")

if st.session_state.last_answer:
    st.subheader("Answer")
    st.write(st.session_state.last_answer)

if st.session_state.last_sources:
    st.subheader("Sources used")
    for i, hit in enumerate(st.session_state.last_sources, start=1):
        with st.expander(
            f"[SOURCE {i}] id={hit.document.id} · date={hit.document.date} · "
            f"similarity={hit.similarity:.4f}"
        ):
            st.text(hit.document.content)
