package br.com.amigavel.tarefas;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;

public final class TaskTransferService {
    private static final int FORMAT_VERSION = 5;
    private static final int MAX_IMPORTED_TASKS = 5_000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final long MAX_FILE_SIZE_BYTES = 5_000_000;

    private final TaskRepository repository;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public TaskTransferService(TaskRepository repository) {
        this.repository = repository;
    }

    public void exportTasks(User user, Path destination, String transferPassword)
            throws SQLException, IOException {
        validateTransferPassword(transferPassword);
        List<TransferTask> tasks = repository.findAll(user).stream()
                .map(task -> new TransferTask(
                        task.title(), task.completed(), task.frequency().name(), task.picture(), task.deadlineAt()))
                .toList();
        byte[] salt = DataEncryption.newSalt();
        byte[] key = deriveKey(transferPassword, salt);
        byte[] plaintext = null;
        try {
            plaintext = mapper.writeValueAsBytes(new TaskPayload(tasks));
            byte[] encrypted = DataEncryption.encryptTransfer(plaintext, key);
            mapper.writeValue(destination.toFile(), new TransferFile(
                    FORMAT_VERSION,
                    Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(encrypted)));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Não foi possível criptografar o arquivo de exportação.", exception);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    public int importTasks(User user, Path source, String transferPassword)
            throws IOException, SQLException {
        validateTransferPassword(transferPassword);
        if (Files.size(source) > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("O arquivo excede o limite de 5 MB.");
        }

        TransferFile transfer = mapper.readValue(source.toFile(), TransferFile.class);
        if (transfer == null || (transfer.version() != FORMAT_VERSION
                && transfer.version() != 4 && transfer.version() != 3 && transfer.version() != 2)
                || transfer.salt() == null || transfer.payload() == null) {
            throw new IllegalArgumentException("Esse arquivo não é compatível com a exportação criptografada.");
        }

        byte[] salt = decodeBase64(transfer.salt(), "salt");
        if (salt.length != 16) {
            throw new IllegalArgumentException("O arquivo de exportação tem um salt inválido.");
        }
        byte[] ciphertext = decodeBase64(transfer.payload(), "conteúdo criptografado");
        byte[] key = deriveKey(transferPassword, salt);
        byte[] plaintext = null;
        try {
            plaintext = DataEncryption.decryptTransfer(ciphertext, key);
            TaskPayload payload = mapper.readValue(plaintext, TaskPayload.class);
            if (payload == null || payload.tasks() == null) {
                throw new IllegalArgumentException("O arquivo não contém uma lista de tarefas.");
            }
            if (payload.tasks().size() > MAX_IMPORTED_TASKS) {
                throw new IllegalArgumentException("O arquivo excede o limite de 5.000 tarefas.");
            }

            for (TransferTask task : payload.tasks()) {
                if (task == null || task.title() == null || task.title().isBlank()
                        || task.title().length() > MAX_TITLE_LENGTH
                        || (task.frequency() != null && !isSupportedFrequency(task.frequency()))
                        || (task.picture() != null && task.picture().length() > 16)
                        || (task.deadlineAt() != null
                        && (task.frequency() == null || !TaskFrequency.ONE_TIME.name().equals(task.frequency())))) {
                    throw new IllegalArgumentException("O arquivo contém uma tarefa vazia ou com mais de 200 caracteres.");
                }
            }
            List<Task> importedTasks = payload.tasks().stream()
                    .map(task -> new Task(0, task.title().trim(), task.completed(),
                            task.frequency() == null ? TaskFrequency.DAILY : TaskFrequency.valueOf(task.frequency()),
                            task.picture() == null || task.picture().isBlank() ? "✍️" : task.picture(),
                            0, task.deadlineAt()))
                    .toList();
            repository.addImported(user, importedTasks);
            return importedTasks.size();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Senha de transferência incorreta ou arquivo danificado.", exception);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] deriveKey(String password, byte[] salt) throws IOException {
        try {
            return DataEncryption.deriveKey(password, salt);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Não foi possível preparar a criptografia da transferência.", exception);
        }
    }

    private byte[] decodeBase64(String value, String fieldName) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("O campo " + fieldName + " do arquivo não é válido.", exception);
        }
    }

    private void validateTransferPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Use uma senha de transferência de 8 a 128 caracteres.");
        }
    }

    private boolean isSupportedFrequency(String frequency) {
        try {
            TaskFrequency.valueOf(frequency);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record TransferFile(int version, String salt, String payload) {
    }

    private record TaskPayload(List<TransferTask> tasks) {
    }

    private record TransferTask(
            String title, boolean completed, String frequency, String picture, Long deadlineAt) {
    }
}
