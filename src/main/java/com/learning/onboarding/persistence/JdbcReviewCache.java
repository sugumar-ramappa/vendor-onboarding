package com.learning.onboarding.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.onboarding.agents.ReviewCache;
import com.learning.onboarding.domain.AgentFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The review cache, in Postgres.
 *
 * <p>Backed by the database rather than memory because a measurement run
 * outlives a JVM. Losing the cache on restart would mean re-spending a day's
 * quota to learn nothing new, which is exactly what the cache exists to prevent.
 *
 * <p>Written through the application's own connection. The agent role has no
 * access at all - a cache the agents could write is a cache an injected
 * instruction could poison, and a poisoned entry would then serve a fabricated
 * finding to every later run of the same application.
 */
@Component
public class JdbcReviewCache implements ReviewCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcReviewCache.class);

    private final JdbcTemplate jdbc;

    /**
     * The cache owns its serialisation rather than sharing the application's
     * ObjectMapper.
     *
     * <p>Deliberate: this is a storage format, and a stored format must not
     * change because someone adjusted how the REST API renders JSON. A config
     * change that silently altered the shape of cached findings would make every
     * existing entry unreadable, and the symptom would be a cache that appears
     * to have stopped working.
     */
    private final ObjectMapper json = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES);

    public JdbcReviewCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Hit> get(String key) {
        List<Hit> rows = jdbc.query("""
                SELECT findings_json, original_latency_ms
                FROM review_cache WHERE cache_key = ?
                """,
                (rs, n) -> new Hit(
                        readFindings(rs.getString("findings_json")),
                        rs.getLong("original_latency_ms")),
                key);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Usage counters, for reporting a hit rate. Not part of the lookup, so a
        // failure here must not turn a hit into a miss.
        try {
            jdbc.update("""
                    UPDATE review_cache
                    SET hit_count = hit_count + 1, last_used_at = now()
                    WHERE cache_key = ?
                    """, key);
        } catch (RuntimeException e) {
            log.warn("could not record cache hit for {}: {}", key, e.getMessage());
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public void put(String key, String area, String promptVersion, String modelName,
                    List<AgentFinding> findings, long originalLatencyMs) {
        try {
            jdbc.update("""
                    INSERT INTO review_cache (cache_key, review_area, prompt_version,
                                              model_name, findings_json, original_latency_ms)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (cache_key) DO NOTHING
                    """,
                    key, area, promptVersion, modelName,
                    json.writeValueAsString(findings), originalLatencyMs);

        } catch (Exception e) {
            // A cache that cannot be written is a slower system, not a broken
            // one. Never let it fail a review that already succeeded.
            log.warn("could not cache {} review: {}", area, e.getMessage());
        }
    }

    private List<AgentFinding> readFindings(String value) {
        try {
            return json.readValue(value, new TypeReference<List<AgentFinding>>() {});
        } catch (Exception e) {
            // A row we cannot deserialise is worse than no row - it would either
            // fail the review or, worse, be silently treated as no findings.
            throw new IllegalStateException("corrupt review_cache entry", e);
        }
    }

    /** For the measurement harness: how much of a run came from cache. */
    public Stats stats() {
        return jdbc.queryForObject("""
                SELECT count(*) AS entries, coalesce(sum(hit_count), 0) AS hits
                FROM review_cache
                """,
                (rs, n) -> new Stats(rs.getInt("entries"), rs.getLong("hits")));
    }

    public record Stats(int entries, long hits) {}
}
