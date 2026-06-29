package cn.ethan.ai.rag;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RagChunkIndexPortTest {

    @Test
    void shouldRespectDashScopeEmbeddingBatchLimit() {
        VectorStore vectorStore = mock(VectorStore.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcTemplateProvider = mock(ObjectProvider.class);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);

        RagChunkIndexPort port = new RagChunkIndexPort(vectorStoreProvider, jdbcTemplateProvider);
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            documents.add(Document.builder().id("doc-" + index).text("chunk-" + index).build());
        }
        port.replaceChunks(RagIngestionDocumentVO.builder()
                .ragId("rag-eval-v1").docId("evaluation-doc").build(), documents);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.times(3)).accept(batches.capture());
        Assertions.assertEquals(List.of(10, 10, 1), batches.getAllValues().stream().map(List::size).toList());
    }
}
