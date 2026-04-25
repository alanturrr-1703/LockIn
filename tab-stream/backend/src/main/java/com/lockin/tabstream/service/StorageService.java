package com.lockin.tabstream.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockin.tabstream.dto.PageSnapshotSummaryDTO;
import com.lockin.tabstream.dto.ParsedPageDTO;
import com.lockin.tabstream.model.PageSnapshot;
import com.lockin.tabstream.repository.PageSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service responsible for persisting and retrieving {@link PageSnapshot} entities.
 *
 * <p>All write operations are annotated with {@code @Transactional} to ensure
 * atomicity. Read operations are marked {@code readOnly = true} for a slight
 * performance benefit (no dirty-checking overhead in Hibernate).
 *
 * <p>Because the JPA entity stores lists as JSON strings (to avoid extra join
 * tables), this service owns the serialisation/deserialisation contract via
 * private helper methods that wrap Jackson's {@link ObjectMapper}.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final PageSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    // Constructor injection — no @Autowired field injection per project conventions
    public StorageService(PageSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository   = repository;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Persist a {@link ParsedPageDTO} as a {@link PageSnapshot} entity.
     *
     * <p>Lists are serialised to JSON strings before saving. The raw HTML stored
     * here is taken from the DTO's cleanText field (the actual raw HTML is not
     * forwarded to the DTO; if callers need to persist rawHtml they should extend
     * this method).
     *
     * @param dto the fully parsed result to save
     * @return the saved entity (with its {@code capturedAt} timestamp set)
     */
    @Transactional
    public PageSnapshot save(ParsedPageDTO dto) {
        log.debug("Persisting PageSnapshot id='{}' url='{}' mode='{}'",
                dto.getId(), dto.getUrl(), dto.getProcessingMode());

        PageSnapshot snapshot = new PageSnapshot();
        snapshot.setId(dto.getId());
        snapshot.setTitle(truncate(dto.getTitle(), 2048));
        snapshot.setUrl(truncate(dto.getUrl(), 4096));
        snapshot.setCleanText(dto.getCleanText());
        snapshot.setHeadingsJson(toJson(dto.getHeadings()));
        snapshot.setLinksJson(toJson(dto.getLinks()));
        snapshot.setParagraphsJson(toJson(dto.getParagraphs()));
        snapshot.setWordCount(dto.getWordCount());
        snapshot.setCharCount(dto.getCharCount());
        snapshot.setMetaDescription(truncate(dto.getMetaDescription(), 1024));
        snapshot.setProcessingMode(dto.getProcessingMode());
        snapshot.setCapturedAt(Instant.now());

        PageSnapshot saved = repository.save(snapshot);
        log.info("Saved PageSnapshot id='{}' wordCount={} capturedAt={}",
                saved.getId(), saved.getWordCount(), saved.getCapturedAt());
        return saved;
    }

    /**
     * Delete every snapshot from the database.
     */
    @Transactional
    public void deleteAll() {
        long count = repository.count();
        repository.deleteAll();
        log.info("Deleted all {} PageSnapshot records", count);
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Return summary DTOs for the 50 most recent snapshots, newest first.
     */
    @Transactional(readOnly = true)
    public List<PageSnapshotSummaryDTO> findAll() {
        List<PageSnapshot> snapshots = repository.findTop50ByOrderByCapturedAtDesc();
        log.debug("findAll() returning {} snapshot summaries", snapshots.size());
        return snapshots.stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
    }

    /**
     * Retrieve a single snapshot by id and reconstruct the full {@link ParsedPageDTO}.
     *
     * @param id UUID of the snapshot
     * @return an {@link Optional} containing the DTO if found, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<ParsedPageDTO> findById(String id) {
        log.debug("Looking up PageSnapshot id='{}'", id);
        return repository.findById(id).map(this::toFullDTO);
    }

    /**
     * Return the total number of snapshots currently stored.
     */
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Map an entity to the lightweight summary DTO used in list responses.
     * Heading and link counts are derived by deserialising the JSON arrays and
     * calling {@code size()} — this is acceptable because the lists are small
     * (capped at 50 and unlimited respectively) and avoids storing redundant
     * count columns.
     */
    private PageSnapshotSummaryDTO toSummaryDTO(PageSnapshot s) {
        int headingCount = sizeOfJsonList(s.getHeadingsJson());
        int linkCount    = sizeOfJsonList(s.getLinksJson());

        return new PageSnapshotSummaryDTO(
                s.getId(),
                s.getTitle(),
                s.getUrl(),
                s.getWordCount(),
                headingCount,
                linkCount,
                s.getCapturedAt() != null ? s.getCapturedAt().toString() : null,
                s.getProcessingMode()
        );
    }

    /**
     * Reconstruct a full {@link ParsedPageDTO} from the entity, deserialising
     * all JSON list fields back to their typed Java representations.
     */
    private ParsedPageDTO toFullDTO(PageSnapshot s) {
        List<String> headings   = fromJsonList(s.getHeadingsJson(),
                new TypeReference<List<String>>() {});
        List<ParsedPageDTO.LinkDTO> links = fromJsonList(s.getLinksJson(),
                new TypeReference<List<ParsedPageDTO.LinkDTO>>() {});
        List<String> paragraphs = fromJsonList(s.getParagraphsJson(),
                new TypeReference<List<String>>() {});

        return new ParsedPageDTO(
                s.getId(),
                s.getTitle(),
                s.getUrl(),
                s.getCapturedAt() != null ? s.getCapturedAt().toString() : null,
                headings,
                links,
                paragraphs,
                s.getCleanText(),
                s.getWordCount(),
                s.getCharCount(),
                s.getMetaDescription(),
                s.getProcessingMode()
        );
    }

    // -------------------------------------------------------------------------
    // JSON serialisation helpers
    // -------------------------------------------------------------------------

    /**
     * Serialise any list to a compact JSON string.
     * Returns {@code "[]"} for null or empty lists so the column is never NULL,
     * which simplifies deserialisation.
     */
    private String toJson(List<?> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("Failed to serialise list to JSON — storing empty array. Error: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Deserialise a JSON string back to a typed list.
     * Returns an empty list if the input is null, blank, or malformed JSON so
     * that callers never receive a null collection.
     *
     * @param json          the stored JSON string
     * @param typeReference Jackson type reference describing the target type
     * @param <T>           the element type of the list
     * @return the deserialised list, never null
     */
    private <T> List<T> fromJsonList(String json, TypeReference<List<T>> typeReference) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            log.warn("Failed to deserialise JSON list — returning empty list. Error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Return the number of elements in a JSON array string without fully
     * deserialising to a typed list. Falls back to 0 on any error.
     */
    private int sizeOfJsonList(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            // readTree is cheaper than a full typed deserialisation for a count
            return objectMapper.readTree(json).size();
        } catch (Exception e) {
            log.warn("Could not determine list size from JSON: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Truncate a string to {@code maxLength} characters.
     * Returns null if input is null, preserving the database column's nullability.
     */
    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
