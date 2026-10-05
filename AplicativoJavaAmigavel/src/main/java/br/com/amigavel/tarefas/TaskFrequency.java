package br.com.amigavel.tarefas;

import java.time.LocalDate;
import java.time.temporal.WeekFields;

public enum TaskFrequency {
    DAILY("Diária"),
    WEEKLY("Semanal"),
    MONTHLY("Mensal"),
    ONE_TIME("Única");

    private final String label;

    TaskFrequency(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String periodKey(LocalDate date) {
        return switch (this) {
            case ONE_TIME -> "ONCE";
            case DAILY -> date.toString();
            case WEEKLY -> {
                WeekFields weekFields = WeekFields.ISO;
                yield date.get(weekFields.weekBasedYear()) + "-W"
                        + String.format("%02d", date.get(weekFields.weekOfWeekBasedYear()));
            }
            case MONTHLY -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
        };
    }

    public static TaskFrequency fromStorage(String value) {
        try {
            return TaskFrequency.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return DAILY;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
