package br.com.amigavel.tarefas;

public record Task(
        long id,
        String title,
        boolean completed,
        TaskFrequency frequency,
        String picture,
        long createdAt,
        Long deadlineAt) {
}
