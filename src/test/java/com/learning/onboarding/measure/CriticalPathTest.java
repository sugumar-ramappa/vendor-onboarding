package com.learning.onboarding.measure;

import com.learning.onboarding.domain.AuditEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconstructing latency from the audit records.
 *
 * <p>These exist because the 2026-08-28 measurement lost its timings. Wall clock
 * is measured with a stopwatch around the graph, so once configurations 1 and 2
 * were regenerated from cache they recorded 8ms and 22ms - and the 28s and 88s
 * they originally took survived only in the prose of a README. A number that
 * lives in prose is not a measurement.
 *
 * <p>The durations were never actually lost: a cache hit returns the original
 * call's latency in its audit entry, so the run still knows what the work cost.
 * What was missing was anything that read them back.
 *
 * <p><b>The rule these tests pin down is the concurrency one.</b> Summing the
 * calls would report configuration 2 as five times the baseline when it is
 * roughly three, and that mistake would flatter the single agent in exactly the
 * comparison the project exists to make.
 */
class CriticalPathTest {

    /** A cache hit, which is the case that matters: no usage, real latency. */
    private static AuditEntry call(long latencyMs) {
        return AuditEntry.ok(UUID.randomUUID(), "F01", "compliance", "v3",
                "stub", "prompt", "[]", latencyMs, Instant.now());
    }

    @Test
    @DisplayName("concurrent reviewers count once, not four times")
    void reviewersRunInParallelSoOnlyTheSlowestCounts() {
        long path = MeasurementHarness.criticalPathMs(
                List.of(call(10_000)),
                List.of(call(20_000), call(45_000), call(30_000), call(25_000)));

        // Not 130s, which is what summing gives. The four reviewers fork onto
        // virtual threads after the gate returns, so the pack waits for the
        // slowest of them and not for all of them.
        assertThat(path).isEqualTo(55_000);
    }

    @Test
    @DisplayName("the gate is sequential, so it adds rather than overlaps")
    void theGateRunsBeforeTheFanOut() {
        assertThat(MeasurementHarness.criticalPathMs(List.of(call(8_000)),
                List.of(call(20_000))))
                .isEqualTo(28_000);
    }

    @Test
    @DisplayName("the single-agent baseline has no gate and one call")
    void oneCallIsItsOwnCriticalPath() {
        assertThat(MeasurementHarness.criticalPathMs(List.of(), List.of(call(28_000))))
                .isEqualTo(28_000);
    }

    @Test
    @DisplayName("a fixture where nothing ran is zero, not an error")
    void noCallsIsZero() {
        // The gate can short-circuit before any reviewer runs. That fixture is
        // already flagged incomplete elsewhere; this must not throw on the way
        // to reporting it.
        assertThat(MeasurementHarness.criticalPathMs(List.of(), List.of())).isZero();
    }
}
