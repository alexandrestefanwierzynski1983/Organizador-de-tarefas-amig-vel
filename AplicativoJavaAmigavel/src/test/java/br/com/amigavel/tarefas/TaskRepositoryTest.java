package br.com.amigavel.tarefas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void taskTitlesAreEncryptedAtRest() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("encrypted.db"));
        User user = new AuthService(database).register("carine", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        repository.add(user, "Compromisso confidencial", TaskFrequency.DAILY, "✍️");

        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT title FROM tasks");
             ResultSet result = statement.executeQuery()) {
            result.next();
            String storedTitle = result.getString("title");
            assertFalse(storedTitle.contains("Compromisso confidencial"));
            assertEquals("enc:v1:", storedTitle.substring(0, "enc:v1:".length()));
        }
        assertEquals("Compromisso confidencial", repository.findAll(user).getFirst().title());
        user.close();
    }

    @Test
    void legacyPlaintextTasksAreEncryptedWhenUserLogsIn() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("legacy.db");
        Database database = new Database(databaseFile);
        AuthService auth = new AuthService(database);
        User original = auth.register("legado", "senha-segura-123");
        long userId = original.id();
        original.close();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO tasks(user_id, title, completed, created_at) VALUES (?, ?, 0, 0)")) {
            statement.setLong(1, userId);
            statement.setString(2, "Tarefa antiga");
            statement.executeUpdate();
        }

        User loggedIn = auth.login("legado", "senha-segura-123").user();

        assertEquals("Tarefa antiga", new TaskRepository(database).findAll(loggedIn).getFirst().title());
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT title FROM tasks");
             ResultSet result = statement.executeQuery()) {
            result.next();
            assertFalse(result.getString("title").contains("Tarefa antiga"));
        }
        loggedIn.close();
    }

    @Test
    void previousDatabaseSchemaIsUpgradedOnStartupAndLogin() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("previous-schema.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        password_hash TEXT NOT NULL,
                        failed_attempts INTEGER NOT NULL DEFAULT 0,
                        locked_until INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        completed INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """);
            try (PreparedStatement insertUser = connection.prepareStatement(
                    "INSERT INTO users(username, password_hash) VALUES (?, ?)");
                 PreparedStatement insertTask = connection.prepareStatement(
                         "INSERT INTO tasks(user_id, title, completed, created_at) VALUES (1, ?, 0, 0)")) {
                insertUser.setString(1, "antiga");
                insertUser.setString(2, BCrypt.hashpw("senha-segura-123", BCrypt.gensalt(4)));
                insertUser.executeUpdate();
                insertTask.setString(1, "Tarefa do banco anterior");
                insertTask.executeUpdate();
            }
        }

        Database upgradedDatabase = new Database(databaseFile);
        User loggedIn = new AuthService(upgradedDatabase).login("antiga", "senha-segura-123").user();

        assertEquals("Tarefa do banco anterior",
                new TaskRepository(upgradedDatabase).findAll(loggedIn).getFirst().title());
        loggedIn.close();
    }

    @Test
    void weeklyAndMonthlyTasksAwardXpOnlyOncePerPeriod() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("progress.db"));
        User user = new AuthService(database).register("progresso", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        Task weeklyTask = repository.findAll(user).stream()
                .filter(task -> task.frequency() == TaskFrequency.WEEKLY)
                .findFirst()
                .orElseThrow();

        TaskRepository.CompletionResult first = repository.updateCompleted(user, weeklyTask.id(), true);
        repository.updateCompleted(user, weeklyTask.id(), false);
        TaskRepository.CompletionResult repeated = repository.updateCompleted(user, weeklyTask.id(), true);

        assertEquals(10, first.xpAwarded());
        assertEquals(0, repeated.xpAwarded());
        assertEquals(10, repeated.progress().totalXp());
        assertEquals(10, repeated.progress().weeklyXp());
        assertEquals(10, repeated.progress().monthlyXp());
        assertEquals(1, repeated.progress().level());
        assertEquals(90, repeated.progress().xpToNextLevel());
        assertTrue(repository.findAll(user).stream()
                .filter(task -> task.id() == weeklyTask.id()).findFirst().orElseThrow().completed());
        user.close();
    }

    @Test
    void reachingRewardGoalTriggersCelebrationOnlyOnce() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("reward-goal.db"));
        User user = new AuthService(database).register("meta", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        repository.add(user, "Meta extra", TaskFrequency.DAILY, "✍️");
        List<Task> tasks = repository.findAll(user);
        TaskRepository.CompletionResult lastResult = null;

        for (Task task : tasks.subList(0, 10)) {
            lastResult = repository.updateCompleted(user, task.id(), true);
        }

        assertEquals(100, lastResult.progress().totalXp());
        assertEquals(2, lastResult.progress().level());
        assertTrue(lastResult.rewardReached());
        repository.updateCompleted(user, tasks.getFirst().id(), false);
        TaskRepository.CompletionResult nextCompletion =
                repository.updateCompleted(user, tasks.getFirst().id(), true);
        assertEquals(0, nextCompletion.xpAwarded());
        assertFalse(nextCompletion.rewardReached());
        user.close();
    }

    @Test
    void oneTimeTaskRemainsCompletedAndAwardsXpOnlyOnce() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("one-time-task.db"));
        User user = new AuthService(database).register("unica", "senha-segura-123");
        TaskRepository repository = new TaskRepository(database);
        long deadlineAt = System.currentTimeMillis() + 86_400_000;
        repository.add(user, "Entregar trabalho escolar", TaskFrequency.ONE_TIME, "📚", deadlineAt);
        Task uniqueTask = repository.findAll(user).stream()
                .filter(task -> task.title().equals("Entregar trabalho escolar"))
                .findFirst()
                .orElseThrow();

        TaskRepository.CompletionResult first = repository.updateCompleted(user, uniqueTask.id(), true);
        repository.updateCompleted(user, uniqueTask.id(), false);
        TaskRepository.CompletionResult repeated = repository.updateCompleted(user, uniqueTask.id(), true);

        assertEquals(10, first.xpAwarded());
        assertEquals(0, repeated.xpAwarded());
        assertTrue(repository.findAll(user).stream()
                .filter(task -> task.id() == uniqueTask.id()).findFirst().orElseThrow().completed());
        assertEquals(deadlineAt, repository.findAll(user).stream()
                .filter(task -> task.id() == uniqueTask.id()).findFirst().orElseThrow().deadlineAt());
        user.close();
    }
}
