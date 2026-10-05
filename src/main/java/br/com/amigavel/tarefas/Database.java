package br.com.amigavel.tarefas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

public final class Database {
    private final String jdbcUrl;

    public Database(Path file) throws IOException, SQLException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        initialize();
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        password_hash TEXT NOT NULL,
                        failed_attempts INTEGER NOT NULL DEFAULT 0,
                        locked_until INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        completed INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """);
            boolean hasEncryptionSalt = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(users)")) {
                while (columns.next()) {
                    if ("encryption_salt".equals(columns.getString("name"))) {
                        hasEncryptionSalt = true;
                        break;
                    }
                }
            }
            if (!hasEncryptionSalt) {
                statement.execute("ALTER TABLE users ADD COLUMN encryption_salt TEXT");
            }
            addColumnIfMissing(statement, "tasks", "frequency",
                    "ALTER TABLE tasks ADD COLUMN frequency TEXT NOT NULL DEFAULT 'DAILY'");
            addColumnIfMissing(statement, "tasks", "completed_period",
                    "ALTER TABLE tasks ADD COLUMN completed_period TEXT");
            addColumnIfMissing(statement, "tasks", "picture",
                    "ALTER TABLE tasks ADD COLUMN picture TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "tasks", "deadline_at",
                    "ALTER TABLE tasks ADD COLUMN deadline_at INTEGER");
            addColumnIfMissing(statement, "users", "reward_title",
                    "ALTER TABLE users ADD COLUMN reward_title TEXT NOT NULL DEFAULT 'Passeio especial ou lazer que eu quero muito'");
            addColumnIfMissing(statement, "users", "reward_target_xp",
                    "ALTER TABLE users ADD COLUMN reward_target_xp INTEGER NOT NULL DEFAULT 100");
            addColumnIfMissing(statement, "users", "reward_notified",
                    "ALTER TABLE users ADD COLUMN reward_notified INTEGER NOT NULL DEFAULT 0");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_xp (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                        period_key TEXT NOT NULL,
                        xp INTEGER NOT NULL DEFAULT 10,
                        completed_at INTEGER NOT NULL,
                        UNIQUE(user_id, task_id, period_key)
                    )
                    """);
            try (var backfill = connection.prepareStatement("""
                    UPDATE tasks SET completed_period = ?
                    WHERE completed = 1 AND completed_period IS NULL
                    """)) {
                backfill.setString(1, TaskFrequency.DAILY.periodKey(LocalDate.now()));
                backfill.executeUpdate();
            }
        }
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String alterSql)
            throws SQLException {
        boolean found = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            statement.execute(alterSql);
        }
    }
}
