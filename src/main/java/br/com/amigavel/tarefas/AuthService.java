package br.com.amigavel.tarefas;

import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

public final class AuthService {
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int BCRYPT_COST = 10;
    private final Database database;
    private final Clock clock;

    public AuthService(Database database) {
        this(database, Clock.systemUTC());
    }

    AuthService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public User register(String username, String password) throws SQLException {
        String normalizedUsername = username == null ? "" : username.trim();
        if (!normalizedUsername.matches("[\\p{L}\\p{N}._-]{3,32}")) {
            throw new IllegalArgumentException("Use um nome de usuário de 3 a 32 caracteres (letras, números, ponto, _ ou -).");
        }
        validatePassword(password);
        if (password.length() < 8) {
            throw new IllegalArgumentException("A senha precisa ter pelo menos 8 caracteres.");
        }

        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST));
        byte[] salt = DataEncryption.newSalt();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO users(username, password_hash, encryption_salt) VALUES (?, ?, ?)")) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, passwordHash);
            statement.setString(3, Base64.getEncoder().encodeToString(salt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UNIQUE constraint failed")) {
                throw new IllegalArgumentException("Esse nome de usuário já está em uso.");
            }
            throw exception;
        }
        try {
            User user = new User(findUserId(normalizedUsername), normalizedUsername,
                    DataEncryption.deriveKey(password, salt));
            try {
                TaskRepository repository = new TaskRepository(database);
                List<Task> starterTasks = new java.util.ArrayList<>(
                        new TaskTemplateCatalog().loadDailyTasks());
                starterTasks.add(new Task(0, "Planejar a semana", false, TaskFrequency.WEEKLY,
                        "🗓️", 0, null));
                starterTasks.add(new Task(0, "Revisar o mês", false, TaskFrequency.MONTHLY,
                        "📅", 0, null));
                repository.addImported(user, starterTasks);
                return user;
            } catch (SQLException exception) {
                user.close();
                throw exception;
            } catch (java.io.IOException exception) {
                user.close();
                throw new SQLException("Não foi possível carregar o modelo inicial de tarefas.", exception);
            }
        } catch (GeneralSecurityException exception) {
            throw new SQLException("Não foi possível preparar a chave de proteção dos dados.", exception);
        }
    }

    public LoginResult login(String username, String password) throws SQLException {
        String normalizedUsername = username == null ? "" : username.trim();
        Account account;
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id, username, password_hash, failed_attempts, locked_until, encryption_salt
                     FROM users WHERE username = ?
                     """)) {
            query.setString(1, normalizedUsername);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, Duration.ZERO);
                }
                long lockedUntilValue = result.getLong("locked_until");
                Long lockedUntil = result.wasNull() ? null : lockedUntilValue;
                account = new Account(
                        result.getLong("id"),
                        result.getString("username"),
                        result.getString("password_hash"),
                        result.getInt("failed_attempts"),
                        lockedUntil,
                        result.getString("encryption_salt"));
            }
        }

        long now = clock.instant().toEpochMilli();
        if (account.lockedUntil() != null && account.lockedUntil() > now) {
            return new LoginResult(LoginStatus.LOCKED, null,
                    Duration.ofMillis(account.lockedUntil() - now));
        }

        if (password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES
                && BCrypt.checkpw(password, account.passwordHash())) {
            try {
                byte[] salt;
                try (Connection connection = database.openConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        if (account.encryptionSalt() == null) {
                            try (PreparedStatement updateSalt = connection.prepareStatement(
                                    "UPDATE users SET encryption_salt = ? WHERE id = ? AND encryption_salt IS NULL")) {
                                updateSalt.setString(1,
                                        Base64.getEncoder().encodeToString(DataEncryption.newSalt()));
                                updateSalt.setLong(2, account.id());
                                updateSalt.executeUpdate();
                            }
                        }
                        try (PreparedStatement readSalt = connection.prepareStatement(
                                "SELECT encryption_salt FROM users WHERE id = ?")) {
                            readSalt.setLong(1, account.id());
                            try (ResultSet result = readSalt.executeQuery()) {
                                if (!result.next()) {
                                    throw new SQLException("A conta não foi encontrada durante a migração.");
                                }
                                salt = decodeSalt(result.getString("encryption_salt"));
                            }
                        }
                        clearFailures(connection, account.id());
                        connection.commit();
                    } catch (SQLException exception) {
                        connection.rollback();
                        throw exception;
                    }
                }
                byte[] encryptionKey = DataEncryption.deriveKey(password, salt);
                return new LoginResult(LoginStatus.SUCCESS,
                        new User(account.id(), account.username(), encryptionKey), Duration.ZERO);
            } catch (GeneralSecurityException exception) {
                throw new SQLException("Não foi possível preparar a chave de proteção dos dados.", exception);
            }
        }

        int nextFailures = account.failedAttempts() + 1;
        Long newLockUntil = nextFailures >= MAX_FAILED_ATTEMPTS
                ? now + LOCK_DURATION.toMillis()
                : null;
        try (Connection connection = database.openConnection()) {
            recordFailure(connection, account.id(), nextFailures, newLockUntil);
        }
        if (newLockUntil != null) {
            return new LoginResult(LoginStatus.LOCKED, null, LOCK_DURATION);
        }
        return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, Duration.ZERO);
    }

    public boolean deleteAccount(User user, String password) throws SQLException {
        if (user == null || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return false;
        }
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT password_hash FROM users WHERE id = ? AND username = ?")) {
            query.setLong(1, user.id());
            query.setString(2, user.username());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !BCrypt.checkpw(password, result.getString("password_hash"))) {
                    return false;
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                delete.setLong(1, user.id());
                return delete.executeUpdate() == 1;
            }
        }
    }

    private long findUserId(String username) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM users WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("A conta foi criada, mas não foi possível carregá-la.");
                }
                return result.getLong("id");
            }
        }
    }

    private byte[] decodeSalt(String encodedSalt) throws SQLException {
        try {
            byte[] salt = Base64.getDecoder().decode(encodedSalt);
            if (salt.length != 16) {
                throw new IllegalArgumentException("Tamanho de salt inválido.");
            }
            return salt;
        } catch (IllegalArgumentException exception) {
            throw new SQLException("O salt de criptografia da conta está inválido.", exception);
        }
    }

    private void clearFailures(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    private void recordFailure(Connection connection, long userId, int attempts, Long lockedUntil)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET failed_attempts = ?, locked_until = ? WHERE id = ?")) {
            statement.setInt(1, attempts);
            if (lockedUntil == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, lockedUntil);
            }
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("A senha deve ter até 72 bytes UTF-8.");
        }
    }

    private record Account(
            long id,
            String username,
            String passwordHash,
            int failedAttempts,
            Long lockedUntil,
            String encryptionSalt) {
    }

    public enum LoginStatus {
        SUCCESS,
        INVALID_CREDENTIALS,
        LOCKED
    }

    public record LoginResult(LoginStatus status, User user, Duration retryAfter) {
    }
}
