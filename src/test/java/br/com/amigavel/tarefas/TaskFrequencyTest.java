package br.com.amigavel.tarefas;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskFrequencyTest {
    @Test
    void weeklyTasksRenewAtIsoWeekBoundary() {
        assertEquals("2026-W53", TaskFrequency.WEEKLY.periodKey(LocalDate.of(2026, 12, 28)));
        assertEquals("2026-W53", TaskFrequency.WEEKLY.periodKey(LocalDate.of(2027, 1, 3)));
        assertEquals("2027-W01", TaskFrequency.WEEKLY.periodKey(LocalDate.of(2027, 1, 4)));
    }

    @Test
    void monthlyTasksRenewAtMonthBoundary() {
        assertEquals("2026-10", TaskFrequency.MONTHLY.periodKey(LocalDate.of(2026, 10, 31)));
        assertEquals("2026-11", TaskFrequency.MONTHLY.periodKey(LocalDate.of(2026, 11, 1)));
    }

    @Test
    void oneTimeTasksUseOnePermanentCompletionPeriod() {
        assertEquals("ONCE", TaskFrequency.ONE_TIME.periodKey(LocalDate.of(2026, 1, 1)));
        assertEquals("ONCE", TaskFrequency.ONE_TIME.periodKey(LocalDate.of(2027, 12, 31)));
    }
}
