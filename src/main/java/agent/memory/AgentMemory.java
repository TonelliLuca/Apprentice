package agent.memory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

public class AgentMemory {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private final Map<String, ToolManual> manualRegistry = new ConcurrentHashMap<>();

    private static final String KEY_TYPE = "memory_type";
    private static final String TYPE_EPISODE = "EPISODE";
    private static final String TYPE_MANUAL_CATALOG = "MANUAL_CATALOG";

    public AgentMemory() {
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        this.embeddingStore = new InMemoryEmbeddingStore<>();
    }


    public void ingestManual(ToolManual manual) {
        manualRegistry.put(manual.getToolName(), manual);

        Metadata metadata = new Metadata();
        metadata.put(KEY_TYPE, TYPE_MANUAL_CATALOG);
        metadata.put("tool_name", manual.getToolName());

        TextSegment segment = TextSegment.from(manual.toCatalogEntry(), metadata);
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }


    public void save(EpisodicMemory memory) {
        String textContent = memory.toTextContent();

        Metadata metadata = new Metadata();
        metadata.put(KEY_TYPE, TYPE_EPISODE);
        metadata.put("outcome", "SUCCESS");
        metadata.put("original_goal", memory.getOriginalGoal());

        TextSegment segment = TextSegment.from(textContent, metadata);
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }


    public List<String> searchManualCatalog(String query, int maxResults) {
        return search(query, maxResults, metadataKey(KEY_TYPE).isEqualTo(TYPE_MANUAL_CATALOG));
    }


    public ToolManual getManualByName(String toolName) {
        return manualRegistry.get(toolName);
    }


    public List<String> retrieveRelevantMemories(String currentGoal, int maxResults) {
        return search(currentGoal, maxResults, metadataKey(KEY_TYPE).isEqualTo(TYPE_EPISODE));
    }

    private List<String> search(String query, int maxResults, Filter filter) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.6) // Soglia di similarità minima
                .filter(filter)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        return result.matches().stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
    }
}