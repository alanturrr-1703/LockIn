package com.lockin.tabstream.service;

import com.lockin.tabstream.dto.PagePayloadDTO;
import com.lockin.tabstream.dto.PageSnapshotSummaryDTO;
import com.lockin.tabstream.dto.ParsedPageDTO;
import com.lockin.tabstream.parser.JSoupPageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the full page-processing pipeline:
 *   1. Generate a unique ID for the record
 *   2. Delegate HTML/text parsing to {@link JSoupPageParser}
 *   3. Persist the result via {@link StorageService}
 *   4. Return the populated {@link ParsedPageDTO} to the caller
 *
 * This service is intentionally thin — it contains no parsing or persistence
 * logic itself, keeping each collaborator focused on a single responsibility.
 */
@Service
public class PageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PageProcessingService.class);

    private final JSoupPageParser parser;
    private final StorageService  storageService;

    public PageProcessingService(JSoupPageParser parser, StorageService storageService) {
        this.parser         = parser;
        this.storageService = storageService;
    }

    // -------------------------------------------------------------------------
    // Core pipeline
    // -------------------------------------------------------------------------

    /**
     * Parse and persist a page payload, returning the fully analysed DTO.
     *
     * @param payload the raw page data received from the Chrome extension
     * @param mode    "rest" or "websocket" — recorded on the snapshot for
     *                provenance / debugging purposes
     * @return the parsed and stored result
     */
    public ParsedPageDTO process(PagePayloadDTO payload, String mode) {
        String id = UUID.randomUUID().toString();
        log.info("Processing page [id={}] url='{}' mode={}", id, payload.getUrl(), mode);

        ParsedPageDTO dto = parser.parse(payload, id, mode);

        try {
            storageService.save(dto);
            log.debug("Snapshot persisted [id={}]", id);
        } catch (Exception e) {
            // Storage failure must NOT prevent the caller from receiving the
            // parsed result — log at WARN and continue.
            log.warn("Failed to persist snapshot [id={}]: {}", id, e.getMessage(), e);
        }

        return dto;
    }

    // -------------------------------------------------------------------------
    // History queries — thin delegation to StorageService
    // -------------------------------------------------------------------------

    /**
     * Return summary DTOs for the 50 most recent snapshots, newest first.
     */
    public List<PageSnapshotSummaryDTO> getHistory() {
        List<PageSnapshotSummaryDTO> history = storageService.findAll();
        log.debug("getHistory() returning {} entries", history.size());
        return history;
    }

    /**
     * Retrieve a single full parsed result by its UUID.
     *
     * @param id the snapshot UUID
     * @return an {@link Optional} containing the DTO if found, empty otherwise
     */
    public Optional<ParsedPageDTO> getById(String id) {
        log.debug("getById('{}')", id);
        return storageService.findById(id);
    }

    /**
     * Delete all stored snapshots.
     */
    public void clearHistory() {
        log.info("Clearing all page history snapshots");
        storageService.deleteAll();
    }
}
