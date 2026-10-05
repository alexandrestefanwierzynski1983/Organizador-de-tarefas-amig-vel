package br.com.amigavel.tarefas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskTransferServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportAndImportTransfersTasksBetweenIsolatedAccounts() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("tasks.db"));
        AuthService auth = new AuthService(database);
        User sender = auth.register("remetente", "senha-segura-123");
        User receiver = auth.register("destino", "senha-segura-456");
        TaskRepository repository = new TaskRepository(database);
        repository.add(sender, "Preparar o material", TaskFrequency.WEEKLY, "🎒");
        long schoolDeadline = LocalDateTime.now().plusDays(5)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        repository.add(sender, "Entregar redação", TaskFrequency.ONE_TIME, "📚", schoolDeadline);
        Task senderTask = repository.findAll(sender).stream()
                .filter(task -> task.title().equals("Preparar o material"))
                .findFirst()
                .orElseThrow();
        repository.updateCompleted(sender, senderTask.id(), true);
        TaskTransferService transfer = new TaskTransferService(repository);
        Path file = temporaryDirectory.resolve("tarefas.json");

        transfer.exportTasks(sender, file, "senha-de-transferencia");
        assertFalse(Files.readString(file).contains("Preparar o material"));
        assertThrows(java.io.IOException.class,
                () -> transfer.importTasks(receiver, file, "senha-errada"));
        int imported = transfer.importTasks(receiver, file, "senha-de-transferencia");

        assertEquals(16, imported);
        assertTrue(repository.findAll(receiver).stream()
                .anyMatch(task -> task.title().equals("Preparar o material") && task.completed()));
        assertTrue(repository.findAll(receiver).stream()
                .anyMatch(task -> task.title().equals("Preparar o material")
                        && task.frequency() == TaskFrequency.WEEKLY));
        assertTrue(repository.findAll(receiver).stream()
                .anyMatch(task -> task.title().equals("Preparar o material")
                        && task.picture().equals("🎒")));
        assertTrue(repository.findAll(receiver).stream()
                .anyMatch(task -> task.title().equals("Entregar redação")
                        && task.frequency() == TaskFrequency.ONE_TIME
                        && task.deadlineAt() != null
                        && task.deadlineAt() == schoolDeadline));
        assertEquals(30, repository.findAll(receiver).size());
        assertEquals(16, repository.findAll(sender).size());
        sender.close();
        receiver.close();
    }

    @Test
    void exportedFileContainsOnlyEncryptedTaskListAndNoAccountOrProgressData() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("private-export.db"));
        AuthService auth = new AuthService(database);
        User user = auth.register("private-user", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        repository.updateReward(user, "Passeio familiar particular", 200);
        TaskTransferService transfer = new TaskTransferService(repository);
        Path file = temporaryDirectory.resolve("private-list.json");
        String transferPassword = "senha-de-exportacao";

        transfer.exportTasks(user, file, transferPassword);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = mapper.readTree(file.toFile());
        Set<String> envelopeFields = iterableToSet(envelope.fieldNames());
        assertEquals(Set.of("version", "salt", "payload"), envelopeFields);

        byte[] salt = Base64.getDecoder().decode(envelope.get("salt").asText());
        byte[] key = DataEncryption.deriveKey(transferPassword, salt);
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("payload").asText());
        byte[] plaintext = DataEncryption.decryptTransfer(ciphertext, key);
        JsonNode listPayload = mapper.readTree(plaintext);
        assertEquals(Set.of("tasks"), iterableToSet(listPayload.fieldNames()));
        assertTrue(listPayload.get("tasks").isArray());
        for (JsonNode task : listPayload.get("tasks")) {
            assertEquals(Set.of("title", "completed", "frequency", "picture", "deadlineAt"),
                    iterableToSet(task.fieldNames()));
        }
        assertFalse(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8).contains(user.username()));
        assertFalse(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8)
                .contains("Passeio familiar particular"));
        java.util.Arrays.fill(key, (byte) 0);
        java.util.Arrays.fill(plaintext, (byte) 0);
        user.close();
    }

    private Set<String> iterableToSet(java.util.Iterator<String> fields) {
        java.util.Set<String> names = new java.util.HashSet<>();
        fields.forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
