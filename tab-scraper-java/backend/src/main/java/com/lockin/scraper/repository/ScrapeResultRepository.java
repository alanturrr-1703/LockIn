package com.lockin.scraper.repository;

import com.lockin.scraper.model.ScrapeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScrapeResultRepository extends JpaRepository<ScrapeResult, String> {

    /** All results ordered newest-first. */
    List<ScrapeResult> findAllByOrderByScrapedAtDesc();

    /** Check if a URL has already been scraped. */
    boolean existsByUrl(String url);

    /** How many times a specific URL has been scraped. */
    long countByUrl(String url);
}
