package br.com.amigavel.tarefas;

import java.time.Duration;

public final class DeadlineProgress {
    public static final Duration FRIENDLY_WARNING = Duration.ofHours(24);

    private DeadlineProgress() {
    }

    public static double remainingFraction(long createdAt, long deadlineAt, long now) {
        long total = deadlineAt - createdAt;
        if (total <= 0 || now >= deadlineAt) {
            return 0;
        }
        if (now <= createdAt) {
            return 1;
        }
        return Math.max(0, Math.min(1, (double) (deadlineAt - now) / total));
    }

    public static String friendlyStatus(long deadlineAt, long now) {
        long remaining = deadlineAt - now;
        if (remaining <= 0) {
            return "Prazo passou — ainda dá para concluir";
        }
        if (remaining <= FRIENDLY_WARNING.toMillis()) {
            return "Prazo próximo: " + formatRemaining(remaining);
        }
        return "Tempo restante: " + formatRemaining(remaining);
    }

    private static String formatRemaining(long milliseconds) {
        Duration remaining = Duration.ofMillis(milliseconds);
        long days = remaining.toDays();
        long hours = remaining.minusDays(days).toHours();
        if (days > 0) {
            return days + (days == 1 ? " dia" : " dias")
                    + (hours > 0 ? " e " + hours + (hours == 1 ? " hora" : " horas") : "");
        }
        long minutes = Math.max(1, remaining.minusHours(hours).toMinutes());
        if (hours > 0) {
            return hours + (hours == 1 ? " hora" : " horas")
                    + (minutes > 0 ? " e " + minutes + " min" : "");
        }
        return minutes + " min";
    }
}
