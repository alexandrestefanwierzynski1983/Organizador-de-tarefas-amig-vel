package br.com.amigavel.tarefas;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineProgressTest {
    @Test
    void circularProgressDrainsToZeroAsDeadlineApproaches() {
        long created = 1_000_000L;
        long deadline = created + Duration.ofDays(10).toMillis();

        assertEquals(1.0, DeadlineProgress.remainingFraction(created, deadline, created));
        assertEquals(0.5, DeadlineProgress.remainingFraction(
                created, deadline, created + Duration.ofDays(5).toMillis()));
        assertEquals(0.0, DeadlineProgress.remainingFraction(created, deadline, deadline));
        assertEquals(0.0, DeadlineProgress.remainingFraction(created, deadline, deadline + 1));
    }

    @Test
    void deadlineWarningIsGentleAndOverdueTasksRemainAvailable() {
        long now = 1_000_000L;
        assertTrue(DeadlineProgress.friendlyStatus(
                now + Duration.ofHours(12).toMillis(), now).startsWith("Prazo próximo:"));
        assertTrue(DeadlineProgress.friendlyStatus(now - 1, now)
                .contains("ainda dá para concluir"));
    }
}
