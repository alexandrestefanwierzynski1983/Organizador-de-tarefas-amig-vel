package br.com.amigavel.tarefas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTemplateCatalogTest {
    @Test
    void defaultJsonTemplateContainsDailyHygieneAndOrganizationTasks() throws Exception {
        var tasks = new TaskTemplateCatalog().loadDailyTasks();

        assertEquals(12, tasks.size());
        assertTrue(tasks.stream().allMatch(task -> task.frequency() == TaskFrequency.DAILY));
        assertFalse(tasks.stream().anyMatch(Task::completed));
        assertTrue(tasks.stream().anyMatch(task -> task.title().equals("Escovar os dentes")));
        assertTrue(tasks.stream().anyMatch(task -> task.title().equals("Organizar a mochila ou bolsa")));
        assertTrue(tasks.stream().anyMatch(task -> task.title().equals("Arrumar a cama (opcional)")));
        assertTrue(tasks.stream().allMatch(task -> task.picture() != null && !task.picture().isBlank()));
    }
}
