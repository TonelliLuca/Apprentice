package agent.memory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

public class AgentMemory {
    private static final Logger logger = LoggerFactory.getLogger(AgentMemory.class);

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
        if (manualRegistry.containsKey(manual.getToolName())) {
            logger.info("♻️ [MEMORY_UPDATE] Updating existing Manual for: '{}'", manual.getToolName());
        } else {
            logger.info("💾 [MEMORY_INGEST] NEW Manual Learned: '{}' -> Saving to Registry & VectorStore.", manual.getToolName());
        }

        manualRegistry.put(manual.getToolName(), manual);

        Metadata metadata = new Metadata();
        metadata.put(KEY_TYPE, TYPE_MANUAL_CATALOG);
        metadata.put("tool_name", manual.getToolName());

        TextSegment segment = TextSegment.from(manual.toCatalogEntry(), metadata);
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }

    public void save(EpisodicMemory memory) {
        logger.info("🧠 [EPISODIC_MEMORY] Saving Experience: Outcome={} | Goal='{}'", memory.getOriginalGoal(), memory.getOriginalGoal());
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
        logger.info("🔍 [MEMORY_SEARCH] Searching Catalog for query: \"{}\"", query);
        List<String> results = search(query, maxResults, metadataKey(KEY_TYPE).isEqualTo(TYPE_MANUAL_CATALOG));

        if (results.isEmpty()) {
            logger.warn("📭 [MEMORY_RESULT] No Manuals found for query: \"{}\"", query);
        } else {
            logger.info("📄 [MEMORY_RESULT] Found {} candidates: \n   Running list: {}", results.size(), results);
        }
        return results;
    }

    public ToolManual getManualByName(String toolName) {
        ToolManual m = manualRegistry.get(toolName);
        if (m != null) {
            logger.debug("✅ [RETRIEVAL_HIT] Found Full Manual for '{}' in Registry.", toolName);
        } else {
            logger.warn("❌ [RETRIEVAL_MISS] Manual for '{}' NOT found in Registry (Discovery needed).", toolName);
        }
        return m;
    }

    public List<String> retrieveRelevantMemories(String currentGoal, int maxResults) {
        return search(currentGoal, maxResults, metadataKey(KEY_TYPE).isEqualTo(TYPE_EPISODE));
    }

    private List<String> search(String query, int maxResults, Filter filter) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.6)
                .filter(filter)
                .build();
        return embeddingStore.search(request).matches().stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
    }
}