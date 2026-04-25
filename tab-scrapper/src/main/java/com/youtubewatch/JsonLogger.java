package com.youtubewatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.nio.file.Paths;

/**
 * JsonLogger
 *
 * Responsible for persisting every {@link VideoMetadata} snapshot to an
 * individual, human-readable JSON file inside the /logs directory.
 *
 * File naming convention:  video_<epoch-millis>.json
 * Example:                 logs/video_1718123456789.json
 */
public class JsonLogger {

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Jackson mapper shared across all write calls (thread-safe after config). */
    private final ObjectMapper mapper;

    /** The logs/ directory handle. Created on construction if absent. */
    private final File logsDir;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Builds a JsonLogger that writes into a {@code logs/} folder relative to
     * the current working directory (i.e. the Gradle project root when launched
     * via {@code ./gradlew run}).
     */
    public JsonLogger() {
        mapper = new ObjectMapper();

        // Pretty-print output so the JSON files are easy to read manually.
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Resolve "logs" relative to wherever the JVM was started from.
        logsDir = Paths.get("logs").toFile();

        // Create the directory (and any missing parents) if it does not exist.
        if (logsDir.mkdirs()) {
            System.out.println("[JsonLogger] Created logs directory: "
                    + logsDir.getAbsolutePath());
        } else {
            System.out.println("[JsonLogger] Using existing logs directory: "
                    + logsDir.getAbsolutePath());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Serialises {@code metadata} to a uniquely-named JSON file.
     *
     * <p>The filename uses {@link System#currentTimeMillis()} so that rapid
     * successive calls (different videos navigated to quickly) never collide.</p>
     *
     * @param metadata the video snapshot to persist
     * @throws JsonLoggerException if the file cannot be written
     */
    public void log(VideoMetadata metadata) {
        // Build the output file path: logs/video_<timestamp>.json
        String filename = "video_" + System.currentTimeMillis() + ".json";
        File outputFile  = new File(logsDir, filename);

        try {
            mapper.writeValue(outputFile, metadata);
            System.out.printf(
                    "[JsonLogger] Saved  ➜  %s%n", outputFile.getAbsolutePath());
        } catch (Exception e) {
            // Wrap so callers don't need to handle checked IO exceptions
            // while still propagating the failure clearly.
            throw new JsonLoggerException(
                    "Failed to write JSON log to " + outputFile.getAbsolutePath(), e);
        }
    }

    /**
     * Returns the absolute path of the logs directory in use.
     * Useful for surfacing the path to the user at startup.
     *
     * @return absolute path string, e.g. {@code /home/user/project/logs}
     */
    public String getLogsDirPath() {
        return logsDir.getAbsolutePath();
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    /**
     * Unchecked exception thrown when a log file cannot be written.
     * Using an unchecked wrapper keeps call-sites clean while still
     * communicating the failure type precisely.
     */
    public static class JsonLoggerException extends RuntimeException {

        public JsonLoggerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
