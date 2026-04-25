package com.lockin.scraper.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Incoming POST body from the Chrome extension — matches exactly what
 * the existing scrapper/extension sends and what yt_receiver.py expects:
 *
 * {
 *   "tiles": [ { "video_id": "...", "title": "...", ... }, ... ],
 *   "page_url": "https://www.youtube.com"
 * }
 */
public class TilesPayload {

    private List<TileData> tiles;

    @JsonProperty("page_url")
    private String pageUrl;

    public TilesPayload() {}

    public List<TileData> getTiles()      { return tiles;   }
    public void setTiles(List<TileData> t){ this.tiles = t; }

    public String getPageUrl()            { return pageUrl;   }
    public void setPageUrl(String p)      { this.pageUrl = p; }

    // ── Nested DTO — one tile from the extension ─────────────────────────────

    public static class TileData {

        @JsonProperty("video_id")
        private String videoId;

        private String title;
        private String channel;
        private String description;

        @JsonProperty("thumbnail_url")
        private String thumbnailUrl;

        private String url;

        @JsonProperty("tile_type")
        private String tileType;

        /** ISO-8601 timestamp set by the extension JS (e.g. "2024-11-01T14:22:05Z"). */
        @JsonProperty("scraped_at")
        private String scrapedAt;

        public TileData() {}

        public String getVideoId()            { return videoId;       }
        public void setVideoId(String v)      { this.videoId = v;     }

        public String getTitle()              { return title;         }
        public void setTitle(String t)        { this.title = t;       }

        public String getChannel()            { return channel;       }
        public void setChannel(String c)      { this.channel = c;     }

        public String getDescription()        { return description;   }
        public void setDescription(String d)  { this.description = d; }

        public String getThumbnailUrl()       { return thumbnailUrl;      }
        public void setThumbnailUrl(String t) { this.thumbnailUrl = t;    }

        public String getUrl()                { return url;           }
        public void setUrl(String u)          { this.url = u;         }

        public String getTileType()           { return tileType;      }
        public void setTileType(String t)     { this.tileType = t;    }

        public String getScrapedAt()          { return scrapedAt;     }
        public void setScrapedAt(String s)    { this.scrapedAt = s;   }
    }
}
