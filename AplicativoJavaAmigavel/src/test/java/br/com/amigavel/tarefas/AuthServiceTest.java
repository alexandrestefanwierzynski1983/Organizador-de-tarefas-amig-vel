package br.com.amigavel.tarefas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void successfulLoginReturnsUserAndResetsPreviousFailures() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("accounts.db"));
        AuthService auth = new AuthService(database);
        User created = auth.register("carine", "senha-segura-123");

        auth.login("carine", "incorreta");
        AuthService.LoginResult result = auth.login("CARINE", "senha-segura-123");

        assertEquals(AuthService.LoginStatus.SUCCESS, result.status());
        assertNotNull(result.user());
        assertEquals(created.id(), result.user().id());
    }

    @Test
    void fifthFailedAttemptLocksAccountForFifteenMinutes() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("locked.db"));
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        AuthService auth = new AuthService(database, Clock.fixed(now, ZoneOffset.UTC));
        auth.register("usuario", "senha-segura-123");

        for (int attempt = 1; attempt < 5; attempt++) {
            assertEquals(AuthService.LoginStatus.INVALID_CREDENTIALS,
                    auth.login("usuario", "senha-errada").status());
        }
        AuthService.LoginResult fifthAttempt = auth.login("usuario", "senha-errada");
        AuthService.LoginResult correctDuringLock = auth.login("usuario", "senha-segura-123");

        assertEquals(AuthService.LoginStatus.LOCKED, fifthAttempt.status());
        assertEquals(Duration.ofMinutes(15), fifthAttempt.retryAfter());
        assertEquals(AuthService.LoginStatus.LOCKED, correctDuringLock.status());
    }

    @Test
    void correctPasswordWorksAfterLockExpires() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("expired-lock.db"));
        Instant lockStart = Instant.parse("2026-01-01T12:00:00Z");
        AuthService beforeExpiry = new AuthService(database, Clock.fixed(lockStart, ZoneOffset.UTC));
        beforeExpiry.register("usuario", "senha-segura-123");
        for (int attempt = 0; attempt < 5; attempt++) {
            beforeExpiry.login("usuario", "senha-errada");
        }
        AuthService afterExpiry = new AuthService(
                database, Clock.fixed(lockStart.plus(Duration.ofMinutes(15)), ZoneOffset.UTC));

        AuthService.LoginResult result = afterExpiry.login("usuario", "senha-segura-123");

        assertEquals(AuthService.LoginStatus.SUCCESS, result.status());
    }

    @Test
    void registrationRejectsShortPasswords() throws Exception {
        AuthService auth = new AuthService(new Database(temporaryDirectory.resolve("validation.db")));

        assertThrows(IllegalArgumentException.class, () -> auth.register("usuario", "curta"));
    }

    @Test
    void newAccountsReceiveEditableDailyRoutineAndSelfCareStarterTasks() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("starter-tasks.db"));
        User user = new AuthService(database).register("novapessoa", "senha-segura-123");

        var tasks = new TaskRepository(database).findAll(user);

        assertEquals(14, tasks.size());
        assertTrue(tasks.stream().noneMatch(Task::completed));
        Set<String> titles = tasks.stream().map(Task::title).collect(Collectors.toSet());
        assertEquals(Set.of(
                "Ir ao banheiro",
                "Lavar as mãos",
                "Escovar os dentes",
                "Lavar o rosto",
                "Tomar banho",
                "Vestir roupa limpa e confortável",
                "Guardar a roupa suja no cesto",
                "Guardar os objetos usados no lugar combinado",
                "Organizar a mochila ou bolsa",
                "Conferir os itens importantes para hoje",
                "Organizar uma pequena área",
                "Arrumar a cama (opcional)",
                "Planejar a semana",
                "Revisar o mês"), titles);
        assertEquals(TaskFrequency.WEEKLY, tasks.stream()
                .filter(task -> task.title().equals("Planejar a semana")).findFirst().orElseThrow().frequency());
        assertEquals(TaskFrequency.MONTHLY, tasks.stream()
                .filter(task -> task.title().equals("Revisar o mês")).findFirst().orElseThrow().frequency());
        user.close();
    }

    @Test
    void accountDeletionRequiresCorrectPasswordAndCascadesUserData() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("account-deletion.db"));
        AuthService auth = new AuthService(database);
        User user = auth.register("apagar", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        assertEquals(14, repository.findAll(user).size());

        assertFalse(auth.deleteAccount(user, "senha-incorreta"));
        assertEquals(14, repository.findAll(user).size());
        assertTrue(auth.deleteAccount(user, "senha-segura-123"));

        assertEquals(AuthService.LoginStatus.INVALID_CREDENTIALS,
                auth.login("apagar", "senha-segura-123").status());
        assertEquals(0, countRows(database, "tasks"));
        assertEquals(0, countRows(database, "task_xp"));
        assertEquals(0, countRows(database, "users"));
        user.close();
    }

    private int countRows(Database database, String table) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
