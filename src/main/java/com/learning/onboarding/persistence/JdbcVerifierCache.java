package com.learning.onboarding.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.onboarding.agents.VerifierCache;
import com.learning.onboarding.domain.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The verifier cache, in Postgres.
 *
 * <p>Deliberately the same shape as {@link JdbcReviewCache}, including its
 * failure behaviour: a cache that cannot be written makes the system slower,
 * never broken, so a write failure is logged and swallowed rather than allowed
 * to fail a challenge that already succeeded.
 */
@Component
public class JdbcVerifierCache implements VerifierCache {

    private static final Logger log = LoggerFactory.getLogger(JdbcVerifierCache.class);

    private final JdbcTemplate jdbc;

    /**
     * Its own ObjectMapper, for the reason {@link JdbcReviewCache} documents:
     * this is a storage format, and a stored format must not change because
     * someone adjusted how the REST API renders JSON.
     */
    private final ObjectMapper json = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES);

    public JdbcVerifierCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Hit> get(String key) {
        List<Hit> rows = jdbc.query("""
                SELECT verdict_json, original_latency_ms
                FROM verifier_cache WHERE cache_key = ?
                """,
                (rs, n) -> new Hit(
                        readVerdict(rs.getString("verdict_json")),
                        rs.getLong("original_latency_ms")),
                key);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Usage counters only. A failure here must not turn a hit into a miss.
        try {
            jdbc.update("""
                    UPDATE verifier_cache
                    SET hit_count = hit_count + 1, last_used_at = now()
                    WHERE cache_key = ?
                    """, key);
        } catch (RuntimeException e) {
            log.warn("could not record verifier cache hit for {}: {}", key, e.getMessage());
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public void put(String key, String promptVersion, String modelName,
                    Verdict verdict, long originalLatencyMs) {
        try {
            jdbc.update("""
                    INSERT INTO verifier_cache (cache_key, prompt_version, model_name,
                                                verdict_json, original_latency_ms)
                    VALUES (?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (cache_key) DO NOTHING
                    """,
                    key, promptVersion, modelName,
                    json.writeValueAsString(verdict), originalLatencyMs);

        } catch (Exception e) {
            log.warn("could not cache verdict: {}", e.getMessage());
        }
    }

    private Verdict readVerdict(String value) {
        try {
            return json.readValue(value, Verdict.class);
        } catch (Exception e) {
            // A row that cannot be deserialised is worse than no row: silently
            // treating it as a missing verdict would drop a challenge, and a
            // dropped challenge reads exactly like a finding that survived one.
            throw new IllegalStateException("corrupt verifier_cache entry", e);
        }
    }

    /** For the measurement harness: how much of a run came from cache. */
    public Stats stats() {
        return jdbc.queryForObject("""
                SELECT count(*) AS entries, coalesce(sum(hit_count), 0) AS hits
                FROM verifier_cache
                """,
                (rs, n) -> new Stats(rs.getInt("entries"), rs.getLong("hits")));
    }

    public record Stats(int entries, long hits) {}
}
