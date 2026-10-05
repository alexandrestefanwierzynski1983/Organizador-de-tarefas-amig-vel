package br.com.amigavel.tarefas;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class TaskTemplateCatalog {
    private static final String DAILY_TEMPLATE_RESOURCE = "/tarefas-diarias-padrao.json";
    private static final int TEMPLATE_VERSION = 1;
    private static final int MAX_TASKS = 100;
    private static final int MAX_TITLE_LENGTH = 200;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public List<Task> loadDailyTasks() throws IOException {
        try (InputStream input = TaskTemplateCatalog.class.getResourceAsStream(DAILY_TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("O modelo de tarefas diárias não foi encontrado no aplicativo.");
            }
            TemplateFile template = mapper.readValue(input, TemplateFile.class);
            if (template == null || template.version() != TEMPLATE_VERSION
                    || !"DAILY".equals(template.frequency())
                    || template.tasks() == null
                    || template.tasks().isEmpty()
                    || template.tasks().size() > MAX_TASKS) {
                throw new IOException("O modelo de tarefas diárias tem um formato inválido.");
            }
            for (TemplateTask task : template.tasks()) {
                if (task == null || task.title() == null || task.title().isBlank()
                        || task.title().length() > MAX_TITLE_LENGTH || task.completed()
                        || task.picture() == null || task.picture().isBlank() || task.picture().length() > 16) {
                    throw new IOException("O modelo contém uma tarefa inválida.");
                }
            }
            return template.tasks().stream()
                    .map(task -> new Task(0, task.title().trim(), false, TaskFrequency.DAILY,
                            task.picture(), 0, null))
                    .toList();
        }
    }

    public List<TaskSuggestion> loadSuggestions() throws IOException {
        List<TaskSuggestion> suggestions = new java.util.ArrayList<>(loadDailyTasks().stream()
                .map(task -> new TaskSuggestion(task.title(), task.picture(), task.frequency()))
                .toList());
        suggestions.add(new TaskSuggestion("Planejar a semana", "🗓️", TaskFrequency.WEEKLY));
        suggestions.add(new TaskSuggestion("Revisar o mês", "📅", TaskFrequency.MONTHLY));
        suggestions.add(new TaskSuggestion("Entregar a tarefa escolar", "📚", TaskFrequency.ONE_TIME));
        return List.copyOf(suggestions);
    }

    private record TemplateFile(int version, String frequency, List<TemplateTask> tasks) {
    }

    private record TemplateTask(String title, String picture, boolean completed) {
    }
}
