package br.com.amigavel.tarefas;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TaskRepository {
    private static final int XP_PER_COMPLETION = 10;
    private static final int XP_PER_LEVEL = 100;

    private final Database database;

    public TaskRepository(Database database) {
        this.database = database;
    }

    public List<Task> findAll(User user) throws SQLException {
        List<StoredTask> storedTasks = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, title, completed, frequency, completed_period, picture, created_at, deadline_at FROM tasks
                        WHERE user_id = ? ORDER BY completed, id DESC
                        """)) {
                    statement.setLong(1, user.id());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            storedTasks.add(new StoredTask(
                                    result.getLong("id"),
                                    result.getString("title"),
                                    result.getInt("completed") != 0,
                                    TaskFrequency.fromStorage(result.getString("frequency")),
                                    result.getString("completed_period"),
                                    result.getString("picture"),
                                    result.getLong("created_at"),
                                    result.getObject("deadline_at") == null ? null : result.getLong("deadline_at")));
                        }
                    }
                }

                List<Task> tasks = new ArrayList<>(storedTasks.size());
                try (PreparedStatement migrate = connection.prepareStatement(
                        "UPDATE tasks SET title = ? WHERE id = ? AND user_id = ?")) {
                    LocalDate today = LocalDate.now();
                    for (StoredTask stored : storedTasks) {
                        String title = stored.title();
                        if (DataEncryption.isEncryptedTask(title)) {
                            title = user.decryptTask(title);
                        } else {
                            migrate.setString(1, user.encryptTask(title));
                            migrate.setLong(2, stored.id());
                            migrate.setLong(3, user.id());
                            migrate.executeUpdate();
                        }
                        boolean completed = stored.completed()
                                && stored.frequency().periodKey(today).equals(stored.completedPeriod());
                        tasks.add(new Task(stored.id(), title, completed, stored.frequency(), stored.picture(),
                                stored.createdAt(), stored.deadlineAt()));
                    }
                }
                tasks.sort(Comparator.comparing(Task::completed)
                        .thenComparing(Task::id, Comparator.reverseOrder()));
                connection.commit();
                return tasks;
            } catch (SQLException | GeneralSecurityException | IllegalArgumentException exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Não foi possível descriptografar ou migrar as tarefas desta conta.", exception);
            }
        }
    }

    public void add(User user, String title, TaskFrequency frequency, String picture, Long deadlineAt)
            throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO tasks(user_id, title, created_at, frequency, picture, deadline_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            long createdAt = System.currentTimeMillis();
            statement.setLong(1, user.id());
            statement.setString(2, encrypt(user, title));
            statement.setLong(3, createdAt);
            statement.setString(4, frequency.name());
            statement.setString(5, normalizePicture(picture));
            if (frequency == TaskFrequency.ONE_TIME && deadlineAt != null) {
                statement.setLong(6, deadlineAt);
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.executeUpdate();
        }
    }

    public void add(User user, String title, TaskFrequency frequency, String picture) throws SQLException {
        add(user, title, frequency, picture, null);
    }

    public void updateTask(User user, long taskId, String title, TaskFrequency frequency, String picture,
                           Long deadlineAt)
            throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE tasks
                     SET title = ?,
                         completed = CASE WHEN frequency <> ? THEN 0 ELSE completed END,
                         completed_period = CASE WHEN frequency <> ? THEN NULL ELSE completed_period END,
                         frequency = ?,
                         picture = ?,
                         deadline_at = ?
                     WHERE id = ? AND user_id = ?
                     """)) {
            statement.setString(1, encrypt(user, title));
            statement.setString(2, frequency.name());
            statement.setString(3, frequency.name());
            statement.setString(4, frequency.name());
            statement.setString(5, normalizePicture(picture));
            if (frequency == TaskFrequency.ONE_TIME && deadlineAt != null) {
                statement.setLong(6, deadlineAt);
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.setLong(7, taskId);
            statement.setLong(8, user.id());
            statement.executeUpdate();
        }
    }

    public CompletionResult updateCompleted(User user, long taskId, boolean completed) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                TaskFrequency frequency;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT frequency FROM tasks WHERE id = ? AND user_id = ?")) {
                    query.setLong(1, taskId);
                    query.setLong(2, user.id());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("A tarefa não foi encontrada para esta conta.");
                        }
                        frequency = TaskFrequency.fromStorage(result.getString("frequency"));
                    }
                }

                int xpAwarded = 0;
                if (completed) {
                    String periodKey = frequency.periodKey(LocalDate.now());
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE tasks SET completed = 1, completed_period = ? WHERE id = ? AND user_id = ?")) {
                        update.setString(1, periodKey);
                        update.setLong(2, taskId);
                        update.setLong(3, user.id());
                        update.executeUpdate();
                    }
                    try (PreparedStatement insertXp = connection.prepareStatement("""
                            INSERT OR IGNORE INTO task_xp(user_id, task_id, period_key, xp, completed_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        insertXp.setLong(1, user.id());
                        insertXp.setLong(2, taskId);
                        insertXp.setString(3, periodKey);
                        insertXp.setInt(4, XP_PER_COMPLETION);
                        insertXp.setLong(5, System.currentTimeMillis());
                        xpAwarded = insertXp.executeUpdate() == 1 ? XP_PER_COMPLETION : 0;
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE tasks SET completed = 0, completed_period = NULL WHERE id = ? AND user_id = ?")) {
                        update.setLong(1, taskId);
                        update.setLong(2, user.id());
                        update.executeUpdate();
                    }
                }

                GamificationProgress progress = readProgress(connection, user.id());
                boolean rewardReached = false;
                if (xpAwarded > 0 && progress.totalXp() >= progress.rewardTargetXp()) {
                    try (PreparedStatement reward = connection.prepareStatement("""
                            UPDATE users SET reward_notified = 1
                            WHERE id = ? AND reward_notified = 0 AND reward_target_xp <= ?
                            """)) {
                        reward.setLong(1, user.id());
                        reward.setInt(2, progress.totalXp());
                        rewardReached = reward.executeUpdate() == 1;
                    }
                }
                connection.commit();
                return new CompletionResult(progress, xpAwarded, rewardReached);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void delete(User user, long taskId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM tasks WHERE id = ? AND user_id = ?")) {
            statement.setLong(1, taskId);
            statement.setLong(2, user.id());
            statement.executeUpdate();
        }
    }

    public void addImported(User user, List<Task> tasks) throws SQLException {
        addImported(user, tasks, true);
    }

    public void addImported(User user, List<Task> tasks, boolean preserveDeadlines) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tasks(user_id, title, completed, created_at, frequency, completed_period, picture, deadline_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                long createdAt = System.currentTimeMillis();
                for (Task task : tasks) {
                    statement.setLong(1, user.id());
                    statement.setString(2, encrypt(user, task.title()));
                    statement.setInt(3, task.completed() ? 1 : 0);
                    statement.setLong(4, createdAt);
                    statement.setString(5, task.frequency().name());
                    statement.setString(6, task.completed()
                            ? task.frequency().periodKey(LocalDate.now())
                            : null);
                    statement.setString(7, normalizePicture(task.picture()));
                    if (preserveDeadlines && task.frequency() == TaskFrequency.ONE_TIME
                            && task.deadlineAt() != null) {
                        statement.setLong(8, task.deadlineAt());
                    } else {
                        statement.setNull(8, java.sql.Types.BIGINT);
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public GamificationProgress progress(User user) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return readProgress(connection, user.id());
        }
    }

    public GamificationProgress updateReward(User user, String title, int targetXp) throws SQLException {
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > 120) {
            throw new IllegalArgumentException("A recompensa deve ter de 1 a 120 caracteres.");
        }
        if (targetXp < XP_PER_LEVEL || targetXp % XP_PER_LEVEL != 0) {
            throw new IllegalArgumentException("Escolha uma meta de XP em múltiplos de 100.");
        }
        if (targetXp <= progress(user).totalXp()) {
            throw new IllegalArgumentException("A meta precisa ser maior que o XP total atual.");
        }
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE users SET reward_title = ?, reward_target_xp = ?, reward_notified = 0
                     WHERE id = ?
                     """)) {
            statement.setString(1, normalizedTitle);
            statement.setInt(2, targetXp);
            statement.setLong(3, user.id());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Não foi possível salvar a recompensa desta conta.");
            }
        }
        return progress(user);
    }

    private GamificationProgress readProgress(Connection connection, long userId) throws SQLException {
        int totalXp;
        int weeklyXp;
        int monthlyXp;
        LocalDate today = LocalDate.now();
        long weekStart = today.with(java.time.DayOfWeek.MONDAY)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long monthStart = today.withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(xp), 0) AS total_xp,
                       COALESCE(SUM(CASE WHEN completed_at >= ? THEN xp ELSE 0 END), 0) AS weekly_xp,
                       COALESCE(SUM(CASE WHEN completed_at >= ? THEN xp ELSE 0 END), 0) AS monthly_xp
                FROM task_xp WHERE user_id = ?
                """)) {
            statement.setLong(1, weekStart);
            statement.setLong(2, monthStart);
            statement.setLong(3, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                totalXp = result.getInt("total_xp");
                weeklyXp = result.getInt("weekly_xp");
                monthlyXp = result.getInt("monthly_xp");
            }
        }
        String rewardTitle;
        int rewardTargetXp;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT reward_title, reward_target_xp FROM users WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("A conta não foi encontrada ao carregar o progresso.");
                }
                rewardTitle = result.getString("reward_title");
                rewardTargetXp = result.getInt("reward_target_xp");
            }
        }
        int xpWithinLevel = totalXp % XP_PER_LEVEL;
        return new GamificationProgress(
                totalXp,
                weeklyXp,
                monthlyXp,
                totalXp / XP_PER_LEVEL + 1,
                xpWithinLevel,
                rewardTitle,
                rewardTargetXp);
    }

    private String encrypt(User user, String title) throws SQLException {
        try {
            return user.encryptTask(title);
        } catch (GeneralSecurityException exception) {
            throw new SQLException("Não foi possível criptografar a tarefa.", exception);
        }
    }

    private String normalizePicture(String picture) {
        return picture == null || picture.isBlank() ? "✍️" : picture;
    }

    public record CompletionResult(GamificationProgress progress, int xpAwarded, boolean rewardReached) {
    }

    private record StoredTask(
            long id,
            String title,
            boolean completed,
            TaskFrequency frequency,
            String completedPeriod,
            String picture,
            long createdAt,
            Long deadlineAt) {
    }
}
