package ru.demo.ragagent.ingest.split;

import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Выбор чанкера одним свойством:
 *   app.rag.splitter: semantic  — семантический (абзацы/предложения)
 *   app.rag.splitter: token     — прежний TokenTextSplitter (чисто по токенам)
 */
@Configuration
public class SplitterConfig {

    @Bean
    public SemanticDocumentSplitter semanticDocumentSplitter(
            @Value("${app.rag.chunking.max-tokens:600}") int maxTokens,
            @Value("${app.rag.chunking.min-tokens:120}") int minTokens,
            @Value("${app.rag.chunking.overlap-sentences:1}") int overlapSentences,
            @Value("${app.rag.chunking.min-chunk-chars:30}") int minChunkChars) {
        return new SemanticDocumentSplitter(maxTokens, minTokens, overlapSentences, minChunkChars);
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    @Bean
    public DocumentTransformer splitter(
            @Value("${app.rag.splitter:semantic}") String splitterKind,
            SemanticDocumentSplitter semantic,
            TokenTextSplitter token) {
        return "token".equalsIgnoreCase(splitterKind) ? token : semantic;
    }
}
