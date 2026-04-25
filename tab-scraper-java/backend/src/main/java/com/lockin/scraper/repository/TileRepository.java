package com.lockin.scraper.repository;

import com.lockin.scraper.model.Tile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TileRepository extends JpaRepository<Tile, String> {

    boolean existsByDedupeKey(String dedupeKey);

    List<Tile> findAllByOrderByScrapedAtDesc();

    long countByPageUrl(String pageUrl);
}
