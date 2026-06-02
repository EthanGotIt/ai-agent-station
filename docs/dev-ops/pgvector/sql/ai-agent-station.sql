CREATE EXTENSION IF NOT EXISTS vector;

-- 查询表；SELECT * FROM information_schema.tables

-- 删除旧的表（如果存在）
DROP TABLE IF EXISTS public.vector_store;

-- 创建新的表，使用UUID作为主键
CREATE TABLE public.vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024)
);

-- 删除旧的表（如果存在）
DROP TABLE IF EXISTS public.vector_store_openai;

-- 创建新的表，使用UUID作为主键
CREATE TABLE public.vector_store_openai (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1024)
);

-- Parent-Child RAG 默认只为 child chunk 建立向量索引，并使用 HNSW 支撑语义召回。
CREATE INDEX IF NOT EXISTS idx_vector_store_openai_embedding_hnsw
    ON public.vector_store_openai
    USING hnsw (embedding vector_cosine_ops);

-- Spring AI Alibaba PostgresSaver checkpoint tables.
-- 开发环境允许 PostgresSaver 自动执行同样的 CREATE IF NOT EXISTS；
-- 生产环境应预建表并将 saver createOption 调整为 CREATE_NONE。
CREATE TABLE IF NOT EXISTS public.GraphThread (
    thread_id UUID PRIMARY KEY,
    thread_name VARCHAR(255),
    is_released BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE TABLE IF NOT EXISTS public.GraphCheckpoint (
    checkpoint_id UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id UUID NOT NULL,
    node_id VARCHAR(255),
    next_node_id VARCHAR(255),
    state_data JSONB NOT NULL,
    state_content_type VARCHAR(100) NOT NULL,
    saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_thread
        FOREIGN KEY(thread_id)
        REFERENCES public.GraphThread(thread_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id
    ON public.GraphCheckpoint(thread_id);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc
    ON public.GraphCheckpoint(thread_id, saved_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased
    ON public.GraphThread(thread_name)
    WHERE is_released = FALSE;
