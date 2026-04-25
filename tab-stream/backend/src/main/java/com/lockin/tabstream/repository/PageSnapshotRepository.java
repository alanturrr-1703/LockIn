package com.lockin.tabstream.repository;

import com.lockin.tabstream.model.PageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link PageSnapshot} entities.
 * Method names follow Spring Data naming conventions — no JPQL needed.
 */
@Repository
public interface PageSnapshotRepository extends JpaRepository<PageSnapshot, String> {

    /**
     * Returns all snapshots ordered newest-first (unbounded).
     * Use with caution on large datasets; prefer findTop50 for UI lists.
     */
    List<PageSnapshot> findAllByOrderByCapturedAtDesc();

    /**
     * Returns the 50 most recent snapshots ordered newest-first.
     * Used by the GET /api/history endpoint to keep response sizes sane.
     */
    List<PageSnapshot> findTop50ByOrderByCapturedAtDesc();

    /**
     * Returns the number of snapshots saved for a given URL.
     * Useful for deduplication checks or analytics.
     */
    long countByUrl(String url);
}
