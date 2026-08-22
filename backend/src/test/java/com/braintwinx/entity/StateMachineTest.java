package com.braintwinx.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.braintwinx.exception.InvalidStateTransitionException;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies the lifecycle state machines required by project brief section 52, which
 * mandates deterministic transitions and forbids arbitrary status changes.
 *
 * <p>These are plain unit tests: no Spring context and no container, so they run in
 * milliseconds and are executed by {@code mvn test} on every build.
 */
@DisplayName("Lifecycle state machines")
class StateMachineTest {

    @Nested
    @DisplayName("ScanStatus")
    class ScanStatusTest {

        @Test
        @DisplayName("follows the documented happy path")
        void happyPath() {
            assertThat(ScanStatus.UPLOADED.canTransitionTo(ScanStatus.VALIDATING)).isTrue();
            assertThat(ScanStatus.VALIDATING.canTransitionTo(ScanStatus.VALIDATED)).isTrue();
            assertThat(ScanStatus.VALIDATED.canTransitionTo(ScanStatus.QUEUED)).isTrue();
            assertThat(ScanStatus.QUEUED.canTransitionTo(ScanStatus.PROCESSING)).isTrue();
            assertThat(ScanStatus.PROCESSING.canTransitionTo(ScanStatus.COMPLETED)).isTrue();
        }

        @Test
        @DisplayName("cannot skip validation on the way to analysis")
        void cannotSkipValidation() {
            // The specific abuse this prevents: an unvalidated file reaching inference.
            assertThat(ScanStatus.UPLOADED.canTransitionTo(ScanStatus.QUEUED)).isFalse();
            assertThat(ScanStatus.UPLOADED.canTransitionTo(ScanStatus.PROCESSING)).isFalse();
            assertThat(ScanStatus.UPLOADED.canTransitionTo(ScanStatus.COMPLETED)).isFalse();
            assertThat(ScanStatus.VALIDATING.canTransitionTo(ScanStatus.PROCESSING)).isFalse();
        }

        @Test
        @DisplayName("COMPLETED is terminal and cannot be revised")
        void completedIsTerminal() {
            // Re-analysis must append a new job and prediction rather than mutate a
            // completed scan, so an already-issued report stays explainable.
            assertThat(ScanStatus.COMPLETED.isTerminal()).isTrue();
            for (ScanStatus target : ScanStatus.values()) {
                assertThat(ScanStatus.COMPLETED.canTransitionTo(target))
                        .as("COMPLETED must not transition to %s", target)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a failed scan may be retried but not declared complete")
        void failedRetriesOnlyToQueued() {
            assertThat(ScanStatus.FAILED.canTransitionTo(ScanStatus.QUEUED)).isTrue();
            assertThat(ScanStatus.FAILED.canTransitionTo(ScanStatus.COMPLETED)).isFalse();
            assertThat(ScanStatus.FAILED.canTransitionTo(ScanStatus.PROCESSING)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ScanStatus.class)
        @DisplayName("no status may transition to itself")
        void noSelfTransition(ScanStatus status) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ScanStatus.class)
        @DisplayName("a null target is always rejected")
        void nullTargetRejected(ScanStatus status) {
            assertThat(status.canTransitionTo(null)).isFalse();
        }

        @Test
        @DisplayName("every status has an explicitly declared transition set")
        void allStatusesCovered() {
            // Guards against a status being added to the enum without a corresponding
            // transition rule, which would otherwise silently default to "no transitions
            // allowed" and strand a scan.
            Set<ScanStatus> terminal = EnumSet.of(ScanStatus.COMPLETED);
            for (ScanStatus status : ScanStatus.values()) {
                if (terminal.contains(status)) {
                    assertThat(status.allowedTargets()).isEmpty();
                } else {
                    assertThat(status.allowedTargets())
                            .as("%s must declare at least one legal transition", status)
                            .isNotEmpty();
                }
            }
        }

        @Test
        @DisplayName("in-flight statuses are exactly the non-terminal working states")
        void inFlightStatuses() {
            assertThat(ScanStatus.VALIDATING.isInFlight()).isTrue();
            assertThat(ScanStatus.VALIDATED.isInFlight()).isTrue();
            assertThat(ScanStatus.QUEUED.isInFlight()).isTrue();
            assertThat(ScanStatus.PROCESSING.isInFlight()).isTrue();

            assertThat(ScanStatus.UPLOADED.isInFlight()).isFalse();
            assertThat(ScanStatus.COMPLETED.isInFlight()).isFalse();
            assertThat(ScanStatus.FAILED.isInFlight()).isFalse();
        }
    }

    @Nested
    @DisplayName("JobStatus")
    class JobStatusTest {

        @Test
        @DisplayName("follows the documented happy path")
        void happyPath() {
            assertThat(JobStatus.QUEUED.canTransitionTo(JobStatus.RUNNING)).isTrue();
            assertThat(JobStatus.RUNNING.canTransitionTo(JobStatus.SUCCEEDED)).isTrue();
        }

        @Test
        @DisplayName("a queued job cannot succeed without running")
        void cannotSucceedWithoutRunning() {
            assertThat(JobStatus.QUEUED.canTransitionTo(JobStatus.SUCCEEDED)).isFalse();
        }

        @Test
        @DisplayName("SUCCEEDED and CANCELLED are terminal")
        void terminalStates() {
            assertThat(JobStatus.SUCCEEDED.isTerminal()).isTrue();
            assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();
            assertThat(JobStatus.QUEUED.isTerminal()).isFalse();
            assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
            assertThat(JobStatus.FAILED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("a failed job returns only to the queue")
        void failedRequeuesOnly() {
            assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.QUEUED)).isTrue();
            assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.SUCCEEDED)).isFalse();
            assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.RUNNING)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(JobStatus.class)
        @DisplayName("no status may transition to itself")
        void noSelfTransition(JobStatus status) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("Scan entity transitions")
    class ScanEntityTest {

        @Test
        @DisplayName("an illegal transition is rejected with a typed exception")
        void illegalTransitionThrows() {
            Scan scan = newScan();

            assertThatThrownBy(() -> scan.transitionTo(ScanStatus.COMPLETED))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("UPLOADED")
                    .hasMessageContaining("COMPLETED");

            // The rejected attempt must leave the entity untouched.
            assertThat(scan.getStatus()).isEqualTo(ScanStatus.UPLOADED);
        }

        @Test
        @DisplayName("failing a scan requires a failure code")
        void failureRequiresCode() {
            Scan scan = newScan();

            assertThatThrownBy(() -> scan.fail(null, "no code supplied"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scan.fail("   ", "blank code"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(scan.getStatus()).isEqualTo(ScanStatus.UPLOADED);
        }

        @Test
        @DisplayName("retrying a failed scan clears the previous error")
        void retryClearsFailure() {
            // Required by the schema constraint that permits a failure code only while the
            // status is FAILED — a retried scan must not carry a stale error.
            Scan scan = newScan();
            scan.fail("INVALID_FILE", "magic bytes did not match declared type");

            assertThat(scan.getStatus()).isEqualTo(ScanStatus.FAILED);
            assertThat(scan.getFailureCode()).isEqualTo("INVALID_FILE");

            scan.transitionTo(ScanStatus.QUEUED);

            assertThat(scan.getStatus()).isEqualTo(ScanStatus.QUEUED);
            assertThat(scan.getFailureCode()).isNull();
            assertThat(scan.getFailureReason()).isNull();
        }

        private Scan newScan() {
            return new Scan(null, java.time.LocalDate.of(2026, 1, 1), ScanType.MRI_T1,
                    "scans/generated-key.png", "image/png", 1024L, "a".repeat(64), null);
        }
    }
}
