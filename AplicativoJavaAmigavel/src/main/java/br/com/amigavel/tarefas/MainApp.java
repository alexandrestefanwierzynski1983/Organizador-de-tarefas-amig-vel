package br.com.amigavel.tarefas;

import javafx.application.Application;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.time.Duration;
import java.util.Optional;

public final class MainApp extends Application {
    private static final String APP_TITLE = "Organizador de tarefas amigável";

    private Stage stage;
    private AuthService authService;
    private TaskRepository taskRepository;
    private TaskTransferService transferService;
    private User currentUser;
    private Label taskFeedback;
    private ListView<Task> taskList;
    private Label progressSummary;
    private Label periodSummary;
    private Label rewardSummary;
    private ProgressBar levelProgress;
    private final TaskTemplateCatalog taskTemplateCatalog = new TaskTemplateCatalog();
    private Timeline deadlineTicker;

    @Override
    public void start(Stage primaryStage) throws IOException, SQLException {
        stage = primaryStage;
        Path databasePath = Path.of(System.getProperty("user.home"), ".tarefas-amigaveis", "tarefas.db");
        Database database = new Database(databasePath);
        authService = new AuthService(database);
        taskRepository = new TaskRepository(database);
        transferService = new TaskTransferService(taskRepository);

        stage.setTitle(APP_TITLE);
        showLogin();
        stage.show();
    }

    private void showLogin() {
        TextField username = new TextField();
        username.setPromptText("Seu nome de usuário");
        username.setAccessibleText("Nome de usuário");
        PasswordField password = new PasswordField();
        password.setPromptText("Sua senha");
        password.setAccessibleText("Senha");
        Label feedback = new Label();
        feedback.getStyleClass().add("feedback");

        Button login = new Button("Entrar");
        login.getStyleClass().add("primary-button");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(event -> {
            try {
                String submittedPassword = password.getText();
                password.clear();
                AuthService.LoginResult result = authService.login(username.getText(), submittedPassword);
                switch (result.status()) {
                    case SUCCESS -> {
                        currentUser = result.user();
                        showTasks();
                    }
                    case INVALID_CREDENTIALS -> feedback.setText("Usuário ou senha incorretos. Confira e tente novamente.");
                    case LOCKED -> feedback.setText(
                            "Acesso pausado por tentativas incorretas. Tente novamente em "
                                    + formatDuration(result.retryAfter()) + ".");
                }
            } catch (SQLException exception) {
                showError("Não foi possível entrar", exception);
            }
        });

        Button register = new Button("Criar uma conta");
        register.setMaxWidth(Double.MAX_VALUE);
        register.setOnAction(event -> showRegistration());

        VBox content = new VBox(14,
                brandHeader(), new Label("Bem-vinda(o)"), new Label("Organize uma tarefa de cada vez."),
                field("Nome de usuário", username), field("Senha", password),
                login, register, feedback);
        content.setMaxWidth(420);
        content.setPadding(new Insets(34));
        content.getStyleClass().add("card");
        VBox root = centered(content);
        setScene(root, 760, 560);
    }

    private void showRegistration() {
        TextField username = new TextField();
        username.setPromptText("De 3 a 32 caracteres");
        username.setAccessibleText("Nome de usuário");
        PasswordField password = new PasswordField();
        password.setPromptText("Pelo menos 8 caracteres");
        password.setAccessibleText("Senha");
        Label feedback = new Label();
        feedback.getStyleClass().add("feedback");

        Button create = new Button("Criar conta");
        create.getStyleClass().add("primary-button");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(event -> {
            try {
                String submittedPassword = password.getText();
                password.clear();
                currentUser = authService.register(username.getText(), submittedPassword);
                showTasks();
            } catch (IllegalArgumentException exception) {
                feedback.setText(exception.getMessage());
            } catch (SQLException exception) {
                showError("Não foi possível criar a conta", exception);
            }
        });

        Button back = new Button("Voltar para entrar");
        back.setMaxWidth(Double.MAX_VALUE);
        back.setOnAction(event -> showLogin());
        VBox content = new VBox(14,
                brandHeader(),
                new Label("Criar sua conta"),
                new Label("Cada conta mantém suas tarefas separadas."),
                field("Nome de usuário", username), field("Senha", password),
                create, back, feedback);
        content.setMaxWidth(420);
        content.setPadding(new Insets(34));
        content.getStyleClass().add("card");
        setScene(centered(content), 760, 560);
    }

    private void showTasks() {
        Label account = new Label("Conta: " + currentUser.username());
        Button logout = new Button("Sair");
        logout.setOnAction(event -> {
            stopDeadlineTicker();
            currentUser.close();
            currentUser = null;
            showLogin();
        });
        Button deleteAccount = new Button("Excluir conta");
        deleteAccount.getStyleClass().add("danger-button");
        deleteAccount.setAccessibleText("Excluir permanentemente a conta e seus dados");
        deleteAccount.setOnAction(event -> deleteAccount());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(12, brandHeader(), account, spacer, deleteAccount, logout);
        top.setAlignment(Pos.CENTER_LEFT);

        TextField newTask = new TextField();
        newTask.setPromptText("Escreva uma tarefa");
        newTask.setAccessibleText("Nova tarefa");
        HBox.setHgrow(newTask, Priority.ALWAYS);
        ComboBox<TaskFrequency> newTaskFrequency = new ComboBox<>();
        newTaskFrequency.getItems().setAll(TaskFrequency.values());
        newTaskFrequency.setValue(TaskFrequency.DAILY);
        newTaskFrequency.setAccessibleText("Frequência da tarefa");
        DatePicker deadlineDate = new DatePicker(LocalDate.now().plusDays(1));
        deadlineDate.setAccessibleText("Data limite");
        TextField deadlineTime = new TextField("17:00");
        deadlineTime.setPromptText("HH:mm");
        deadlineTime.setAccessibleText("Horário limite");
        CheckBox hasDeadline = new CheckBox("Definir prazo");
        deadlineDate.disableProperty().bind(hasDeadline.selectedProperty().not());
        deadlineTime.disableProperty().bind(hasDeadline.selectedProperty().not());
        hasDeadline.selectedProperty().addListener((observable, wasSelected, selected) -> {
            if (selected && deadlineDate.getValue() == null) {
                deadlineDate.setValue(LocalDate.now().plusDays(1));
            }
        });
        HBox deadlineControls = new HBox(10,
                new Label("Prazo escolar ou pessoal (opcional):"),
                hasDeadline,
                deadlineDate,
                deadlineTime);
        deadlineControls.setAlignment(Pos.CENTER_LEFT);
        deadlineControls.visibleProperty().bind(newTaskFrequency.valueProperty()
                .isEqualTo(TaskFrequency.ONE_TIME));
        deadlineControls.managedProperty().bind(deadlineControls.visibleProperty());
        newTaskFrequency.valueProperty().addListener((observable, previous, selected) -> {
            if (selected == TaskFrequency.ONE_TIME && deadlineDate.getValue() == null) {
                deadlineDate.setValue(LocalDate.now().plusDays(1));
            }
        });
        Label selectedPicture = new Label("✍️");
        selectedPicture.getStyleClass().add("selected-picture");
        selectedPicture.setAccessibleText("Tarefa criada por escrito");
        Button choosePicture = new Button("Escolher por figura");
        choosePicture.setOnAction(event -> chooseTaskPicture(
                newTask, newTaskFrequency, selectedPicture));
        Button add = new Button("Adicionar");
        add.getStyleClass().add("primary-button");
        add.setOnAction(event -> addTask(newTask, newTaskFrequency, selectedPicture,
                hasDeadline, deadlineDate, deadlineTime));
        newTask.setOnAction(event -> addTask(newTask, newTaskFrequency, selectedPicture,
                hasDeadline, deadlineDate, deadlineTime));
        HBox taskEntryMain = new HBox(10, newTask, choosePicture, selectedPicture, newTaskFrequency, add);
        taskEntryMain.setAlignment(Pos.CENTER_LEFT);
        VBox taskEntry = new VBox(8, taskEntryMain, deadlineControls);

        taskFeedback = new Label("Cada tarefa concluída vale 10 XP. Não há pressa: avance no seu ritmo.");
        taskFeedback.getStyleClass().add("feedback");
        VBox gamification = gamificationPanel();
        taskList = new ListView<>();
        taskList.setAccessibleText("Lista de tarefas");
        taskList.setCellFactory(ignored -> new TaskCell());
        VBox.setVgrow(taskList, Priority.ALWAYS);

        Button export = new Button("Exportar tarefas");
        export.setOnAction(event -> exportTasks());
        Button importButton = new Button("Importar tarefas");
        importButton.setOnAction(event -> importTasks());
        HBox transferActions = new HBox(10, export, importButton);

        VBox content = new VBox(16, top, gamification, taskEntry, taskFeedback, taskList, transferActions);
        content.setPadding(new Insets(28, 38, 32, 38));
        content.getStyleClass().add("task-page");
        setScene(content, 960, 820);
        refreshTasks();
        refreshProgress();
        startDeadlineTicker();
        newTask.requestFocus();
    }

    private VBox gamificationPanel() {
        progressSummary = new Label();
        progressSummary.getStyleClass().add("progress-summary");
        levelProgress = new ProgressBar(0);
        levelProgress.setMaxWidth(Double.MAX_VALUE);
        levelProgress.setAccessibleText("Progresso para o próximo nível");
        periodSummary = new Label();
        periodSummary.getStyleClass().add("feedback");
        rewardSummary = new Label();
        rewardSummary.setWrapText(true);
        rewardSummary.getStyleClass().add("reward-summary");
        Button editReward = new Button("Escolher meu lazer");
        editReward.setOnAction(event -> editReward());
        VBox panel = new VBox(8,
                new Label("Seu progresso"),
                progressSummary,
                levelProgress,
                periodSummary,
                rewardSummary,
                editReward);
        panel.getStyleClass().add("progress-card");
        return panel;
    }

    private void refreshProgress() {
        try {
            GamificationProgress progress = taskRepository.progress(currentUser);
            progressSummary.setText("Nível " + progress.level() + "  •  "
                    + progress.totalXp() + " XP no total  •  faltam "
                    + progress.xpToNextLevel() + " XP para o próximo nível");
            levelProgress.setProgress(progress.levelProgress());
            periodSummary.setText("Esta semana: " + progress.weeklyXp() + " XP"
                    + "     Este mês: " + progress.monthlyXp() + " XP");
            rewardSummary.setText("Sua meta de lazer: " + progress.rewardTitle()
                    + " — " + progress.rewardTargetXp() + " XP");
        } catch (SQLException exception) {
            showError("Não foi possível carregar o progresso", exception);
        }
    }

    private void editReward() {
        try {
            GamificationProgress progress = taskRepository.progress(currentUser);
            TextField title = new TextField(progress.rewardTitle());
            title.setPromptText("Ex.: passeio especial em família");
            TextField target = new TextField(Integer.toString(progress.rewardTargetXp()));
            target.setPromptText("Ex.: 100");
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Minha meta de lazer");
            dialog.setHeaderText("Escolha algo gostoso para celebrar seu esforço");
            dialog.getDialogPane().setContent(new VBox(10,
                    new Label("Passeio ou lazer que você quer muito"),
                    title,
                    new Label("Meta de XP (múltiplos de 100; seu total atual: " + progress.totalXp() + " XP)"),
                    target));
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
                try {
                    int targetXp = Integer.parseInt(target.getText().trim());
                    taskRepository.updateReward(currentUser, title.getText(), targetXp);
                    refreshProgress();
                    taskFeedback.setText("Meta de lazer atualizada. Uma tarefa por vez, no seu ritmo.");
                } catch (NumberFormatException exception) {
                    showError("Meta de XP inválida", new IllegalArgumentException("Digite a meta como um número inteiro."));
                } catch (IllegalArgumentException | SQLException exception) {
                    showError("Não foi possível salvar a meta", exception);
                }
            });
        } catch (SQLException exception) {
            showError("Não foi possível carregar a meta", exception);
        }
    }

    private void chooseTaskPicture(TextField title, ComboBox<TaskFrequency> frequency, Label selectedPicture) {
        List<TaskSuggestion> suggestions;
        try {
            suggestions = taskTemplateCatalog.loadSuggestions();
        } catch (IOException exception) {
            showError("Não foi possível abrir as figuras de tarefas", exception);
            return;
        }

        Dialog<TaskSuggestion> dialog = new Dialog<>();
        dialog.setTitle("Escolher uma tarefa por figura");
        dialog.setHeaderText("Selecione uma figura para preencher a tarefa; você poderá editar o texto.");
        FlowPane gallery = new FlowPane(12, 12);
        gallery.setPrefWrapLength(540);
        gallery.getStyleClass().add("picture-gallery");
        for (TaskSuggestion suggestion : suggestions) {
            Label picture = new Label(suggestion.picture());
            picture.getStyleClass().add("picture-option-icon");
            Label caption = new Label(suggestion.title());
            caption.getStyleClass().add("picture-option-caption");
            caption.setWrapText(true);
            caption.setMaxWidth(130);
            Button option = new Button();
            option.setGraphic(new VBox(8, picture, caption));
            option.getStyleClass().add("picture-option");
            option.setAccessibleText(suggestion.title() + ", " + suggestion.frequency().label());
            option.setOnAction(event -> {
                dialog.setResult(suggestion);
                dialog.close();
            });
            gallery.getChildren().add(option);
        }
        ScrollPane galleryScroll = new ScrollPane(gallery);
        galleryScroll.setFitToWidth(true);
        galleryScroll.setPrefViewportHeight(420);
        galleryScroll.setAccessibleText("Galeria de figuras de tarefas");
        dialog.getDialogPane().setContent(galleryScroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(suggestion -> {
            title.setText(suggestion.title());
            frequency.setValue(suggestion.frequency());
            selectedPicture.setText(suggestion.picture());
            selectedPicture.setAccessibleText("Figura selecionada: " + suggestion.title());
            title.requestFocus();
            title.positionCaret(title.getText().length());
        });
    }

    private void addTask(
            TextField input,
            ComboBox<TaskFrequency> frequency,
            Label selectedPicture,
            CheckBox hasDeadline,
            DatePicker deadlineDate,
            TextField deadlineTime) {
        String title = input.getText().trim();
        if (title.isEmpty()) {
            taskFeedback.setText("Escreva uma tarefa antes de adicionar.");
            input.requestFocus();
            return;
        }
        if (title.length() > 200) {
            taskFeedback.setText("Use até 200 caracteres para a tarefa.");
            return;
        }
        try {
            Long deadlineAt = selectedDeadline(frequency.getValue(), hasDeadline.isSelected(),
                    deadlineDate.getValue(), deadlineTime.getText());
            taskRepository.add(currentUser, title, frequency.getValue(), selectedPicture.getText(), deadlineAt);
            input.clear();
            selectedPicture.setText("✍️");
            selectedPicture.setAccessibleText("Tarefa criada por escrito");
            taskFeedback.setText("Tarefa adicionada.");
            refreshTasks();
            input.requestFocus();
        } catch (IllegalArgumentException exception) {
            taskFeedback.setText(exception.getMessage());
        } catch (SQLException exception) {
            showError("Não foi possível adicionar a tarefa", exception);
        }
    }

    private Long selectedDeadline(
            TaskFrequency frequency,
            boolean enabled,
            LocalDate date,
            String timeText) {
        if (frequency != TaskFrequency.ONE_TIME || !enabled) {
            return null;
        }
        if (date == null) {
            throw new IllegalArgumentException("Escolha a data limite ou desmarque “Definir prazo”.");
        }
        try {
            LocalTime time = LocalTime.parse(timeText.trim(), DateTimeFormatter.ofPattern("HH:mm"));
            return LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Informe o horário no formato 17:00.", exception);
        }
    }

    private void editTask(Task task) {
        TextField title = new TextField(task.title());
        title.setPromptText("Nome da tarefa");
        ComboBox<TaskFrequency> frequency = new ComboBox<>();
        frequency.getItems().setAll(TaskFrequency.values());
        frequency.setValue(task.frequency());
        Label selectedPicture = new Label(normalizedPicture(task.picture()));
        selectedPicture.getStyleClass().add("selected-picture");
        CheckBox hasDeadline = new CheckBox("Definir prazo");
        hasDeadline.setSelected(task.deadlineAt() != null);
        DatePicker deadlineDate = new DatePicker(task.deadlineAt() == null
                ? LocalDate.now().plusDays(1)
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(task.deadlineAt()),
                ZoneId.systemDefault()).toLocalDate());
        TextField deadlineTime = new TextField(task.deadlineAt() == null
                ? "17:00"
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(task.deadlineAt()),
                ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        deadlineDate.disableProperty().bind(hasDeadline.selectedProperty().not());
        deadlineTime.disableProperty().bind(hasDeadline.selectedProperty().not());
        hasDeadline.selectedProperty().addListener((observable, wasSelected, selected) -> {
            if (selected && deadlineDate.getValue() == null) {
                deadlineDate.setValue(LocalDate.now().plusDays(1));
            }
        });
        HBox deadlineControls = new HBox(8, hasDeadline, deadlineDate, deadlineTime);
        deadlineControls.visibleProperty().bind(frequency.valueProperty().isEqualTo(TaskFrequency.ONE_TIME));
        deadlineControls.managedProperty().bind(deadlineControls.visibleProperty());
        Button choosePicture = new Button("Trocar figura");
        choosePicture.setOnAction(event -> chooseTaskPicture(title, frequency, selectedPicture));
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modificar tarefa");
        dialog.setHeaderText("Edite o texto ou a frequência da tarefa");
        dialog.getDialogPane().setContent(new VBox(10,
                new Label("Tarefa"), title,
                new Label("Figura"), new HBox(10, selectedPicture, choosePicture),
                new Label("Repetir"), frequency,
                new Label("Prazo escolar ou pessoal (opcional)"),
                deadlineControls));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            String updatedTitle = title.getText().trim();
            if (updatedTitle.isEmpty() || updatedTitle.length() > 200) {
                taskFeedback.setText("A tarefa precisa ter de 1 a 200 caracteres.");
                return;
            }
            try {
                Long deadlineAt = selectedDeadline(frequency.getValue(), hasDeadline.isSelected(),
                        deadlineDate.getValue(), deadlineTime.getText());
                taskRepository.updateTask(currentUser, task.id(), updatedTitle, frequency.getValue(),
                        selectedPicture.getText(), deadlineAt);
                taskFeedback.setText("Tarefa modificada.");
                refreshTasks();
            } catch (IllegalArgumentException exception) {
                taskFeedback.setText(exception.getMessage());
            } catch (SQLException exception) {
                showError("Não foi possível modificar a tarefa", exception);
            }
        });
    }

    private void deleteTask(Task task) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Excluir tarefa");
        confirmation.setHeaderText("Excluir esta tarefa?");
        confirmation.setContentText(task.title());
        confirmation.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            try {
                taskRepository.delete(currentUser, task.id());
                taskFeedback.setText("Tarefa excluída.");
                refreshTasks();
            } catch (SQLException exception) {
                showError("Não foi possível excluir a tarefa", exception);
            }
        });
    }

    private void exportTasks() {
        FileChooser chooser = jsonChooser("Exportar tarefas");
        chooser.setInitialFileName("minhas-tarefas.json");
        var destination = chooser.showSaveDialog(stage);
        if (destination == null) {
            return;
        }
        Optional<String> password = promptTransferPassword("Criar senha para o arquivo", true);
        if (password.isEmpty()) {
            return;
        }
        try {
            transferService.exportTasks(currentUser, destination.toPath(), password.get());
            taskFeedback.setText("Tarefas criptografadas e exportadas para " + destination.getName() + ".");
        } catch (IllegalArgumentException exception) {
            showError("Senha de transferência inválida", exception);
        } catch (IOException | SQLException exception) {
            showError("Não foi possível exportar as tarefas", exception);
        }
    }

    private void importTasks() {
        FileChooser chooser = jsonChooser("Importar tarefas");
        var source = chooser.showOpenDialog(stage);
        if (source == null) {
            return;
        }
        Optional<String> password = promptTransferPassword("Senha do arquivo de transferência", false);
        if (password.isEmpty()) {
            return;
        }
        try {
            int imported = transferService.importTasks(currentUser, source.toPath(), password.get());
            refreshTasks();
            taskFeedback.setText(imported + (imported == 1
                    ? " tarefa importada. As tarefas existentes foram mantidas."
                    : " tarefas importadas. As tarefas existentes foram mantidas."));
        } catch (IOException | SQLException | IllegalArgumentException exception) {
            showError("Não foi possível importar as tarefas", exception);
        }
    }

    private Optional<String> promptTransferPassword(String title, boolean confirmPassword) {
        PasswordField password = new PasswordField();
        password.setPromptText(confirmPassword ? "Crie uma senha com 8 a 128 caracteres" : "Senha do arquivo");
        PasswordField confirmation = new PasswordField();
        confirmation.setPromptText("Digite a senha novamente");
        VBox content = new VBox(10, new Label(title), password);
        if (confirmPassword) {
            content.getChildren().add(confirmation);
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(content);
        ButtonType accept = new ButtonType("Continuar", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(accept, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == accept ? password.getText() : null);
        Optional<String> result = dialog.showAndWait();
        if (confirmPassword && result.isPresent() && !result.get().equals(confirmation.getText())) {
            password.clear();
            confirmation.clear();
            showError("As senhas não coincidem", new IllegalArgumentException("Digite a mesma senha nos dois campos."));
            return Optional.empty();
        }
        password.clear();
        confirmation.clear();
        return result;
    }

    private FileChooser jsonChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Arquivo JSON", "*.json"));
        return chooser;
    }

    private void refreshTasks() {
        try {
            taskList.getItems().setAll(taskRepository.findAll(currentUser));
        } catch (SQLException exception) {
            showError("Não foi possível carregar as tarefas", exception);
        }
    }

    private void startDeadlineTicker() {
        stopDeadlineTicker();
        deadlineTicker = new Timeline(new KeyFrame(
                javafx.util.Duration.minutes(1),
                event -> refreshTasks()));
        deadlineTicker.setCycleCount(Timeline.INDEFINITE);
        deadlineTicker.play();
    }

    private void stopDeadlineTicker() {
        if (deadlineTicker != null) {
            deadlineTicker.stop();
            deadlineTicker = null;
        }
    }

    private HBox deadlineIndicator(Task task) {
        StackPane ring = new StackPane();
        ring.setMinSize(40, 40);
        ring.setPrefSize(40, 40);
        ring.setMaxSize(40, 40);
        Circle track = new Circle(16);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.web("#dfe5ed"));
        track.setStrokeWidth(4);
        Arc remainingArc = new Arc(20, 20, 16, 16, -90,
                task.completed() ? 360 : DeadlineProgress.remainingFraction(
                        task.createdAt(), task.deadlineAt(), System.currentTimeMillis()) * 360);
        remainingArc.setType(ArcType.OPEN);
        remainingArc.setFill(Color.TRANSPARENT);
        remainingArc.setStrokeWidth(4);
        long remainingMillis = task.deadlineAt() - System.currentTimeMillis();
        Color ringColor = task.completed()
                ? Color.web("#368b69")
                : remainingMillis <= 0
                ? Color.web("#8b91a0")
                : remainingMillis <= DeadlineProgress.FRIENDLY_WARNING.toMillis()
                ? Color.web("#d88924")
                : Color.web("#7551b5");
        remainingArc.setStroke(ringColor);
        ring.getChildren().addAll(track, remainingArc);

        String status = task.completed()
                ? "Prazo concluído"
                : DeadlineProgress.friendlyStatus(task.deadlineAt(), System.currentTimeMillis());
        LocalDateTime deadline = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(task.deadlineAt()), ZoneId.systemDefault());
        Label text = new Label("Prazo " + deadline.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                + " • " + status);
        text.getStyleClass().add(task.completed() ? "deadline-complete"
                : remainingMillis <= 0 ? "deadline-overdue"
                : remainingMillis <= DeadlineProgress.FRIENDLY_WARNING.toMillis()
                ? "deadline-near" : "deadline-status");
        text.setWrapText(true);
        ring.setAccessibleText(task.completed() ? "Prazo concluído"
                : "Tempo restante: " + status);
        HBox indicator = new HBox(8, ring, text);
        indicator.setAlignment(Pos.CENTER_LEFT);
        return indicator;
    }

    private VBox field(String label, javafx.scene.control.Control control) {
        Label title = new Label(label);
        title.getStyleClass().add("field-label");
        return new VBox(6, title, control);
    }

    private HBox brandHeader() {
        SVGPath infinity = new SVGPath();
        infinity.setContent("M 8 30 C 18 10 32 10 44 30 C 56 50 70 50 82 30 C 70 10 56 10 44 30 C 32 50 18 50 8 30");
        infinity.setFill(Color.TRANSPARENT);
        infinity.setStroke(new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0.00, Color.web("#e34850")),
                new Stop(0.22, Color.web("#ed8b35")),
                new Stop(0.42, Color.web("#e6c341")),
                new Stop(0.62, Color.web("#38a879")),
                new Stop(0.82, Color.web("#3988d4")),
                new Stop(1.00, Color.web("#8855bd"))));
        infinity.setStrokeWidth(8);
        infinity.setStrokeLineCap(StrokeLineCap.ROUND);
        infinity.setAccessibleText("Símbolo do infinito colorido");

        Label name = new Label(APP_TITLE);
        name.getStyleClass().add("brand-title");
        name.setWrapText(true);
        Label tagline = new Label("Neurodiversidade • cada pessoa no seu ritmo");
        tagline.getStyleClass().add("brand-tagline");
        VBox identity = new VBox(4, name, tagline);
        HBox header = new HBox(12, infinity, identity);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("brand-header");
        return header;
    }

    private VBox centered(VBox content) {
        VBox root = new VBox(content);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("page");
        return root;
    }

    private void setScene(javafx.scene.Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(500);
    }

    private String formatDuration(Duration duration) {
        long minutes = Math.max(1, (duration.toSeconds() + 59) / 60);
        return minutes + (minutes == 1 ? " minuto" : " minutos");
    }

    private void showError(String title, Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(APP_TITLE);
        alert.setHeaderText(title);
        alert.setContentText(exception.getMessage() == null ? "Ocorreu um erro inesperado." : exception.getMessage());
        alert.showAndWait();
    }

    private final class TaskCell extends ListCell<Task> {
        @Override
        protected void updateItem(Task task, boolean empty) {
            super.updateItem(task, empty);
            if (empty || task == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            CheckBox completed = new CheckBox();
            completed.setAccessibleText("Concluída: " + task.title());
            completed.setSelected(task.completed());
            completed.setOnAction(event -> {
                try {
                    TaskRepository.CompletionResult result =
                            taskRepository.updateCompleted(currentUser, task.id(), completed.isSelected());
                    if (completed.isSelected() && result.xpAwarded() > 0) {
                        int previousLevel = (result.progress().totalXp() - result.xpAwarded()) / 100 + 1;
                        taskFeedback.setText("Tarefa concluída: +" + result.xpAwarded() + " XP. Bom trabalho!");
                        if (result.progress().level() > previousLevel) {
                            showCelebration("Você chegou ao nível " + result.progress().level() + "!",
                                    "Cada passo conta. Parabéns por continuar no seu ritmo.");
                        }
                        if (result.rewardReached()) {
                            showCelebration("Sua meta de lazer chegou!",
                                    "Você alcançou " + result.progress().rewardTargetXp() + " XP. "
                                            + "Se for um bom momento para você, celebre com: "
                                            + result.progress().rewardTitle() + ".");
                        }
                    } else if (completed.isSelected()) {
                        taskFeedback.setText("Esta tarefa já rendeu XP neste período. Seu progresso foi mantido.");
                    } else {
                        taskFeedback.setText("Tarefa reaberta. O XP já conquistado continua com você.");
                    }
                    refreshProgress();
                    refreshTasks();
                } catch (SQLException exception) {
                    showError("Não foi possível atualizar a tarefa", exception);
                }
            });

            Label title = new Label(task.title());
            title.setWrapText(true);
            title.getStyleClass().add("task-title");
            if (task.completed()) {
                title.getStyleClass().add("completed-task");
            }
            HBox.setHgrow(title, Priority.ALWAYS);
            Label picture = new Label(normalizedPicture(task.picture()));
            picture.getStyleClass().add("task-picture");
            picture.setAccessibleText("Figura da tarefa: " + task.title());
            Label frequency = new Label(task.frequency().label());
            frequency.getStyleClass().add("frequency-badge");
            frequency.setAccessibleText("Frequência: " + task.frequency().label());

            Button edit = new Button("Modificar");
            edit.setOnAction(event -> editTask(task));
            Button delete = new Button("Excluir");
            delete.setOnAction(event -> deleteTask(task));
            HBox row;
            if (task.deadlineAt() == null) {
                row = new HBox(12, completed, picture, title, frequency, edit, delete);
            } else {
                VBox taskDetails = new VBox(4, title, deadlineIndicator(task));
                HBox.setHgrow(taskDetails, Priority.ALWAYS);
                row = new HBox(12, completed, picture, taskDetails, frequency, edit, delete);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 8, 10, 8));
            setGraphic(row);
            setText(null);
        }
    }

    private void showCelebration(String title, String message) {
        Alert celebration = new Alert(Alert.AlertType.INFORMATION);
        celebration.setTitle(APP_TITLE);
        celebration.setHeaderText(title);
        celebration.setContentText(message);
        celebration.showAndWait();
    }

    private void deleteAccount() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Excluir conta");
        confirmation.setHeaderText("Excluir permanentemente a conta de " + currentUser.username() + "?");
        confirmation.setContentText(
                "Esta ação apagará todas as tarefas, o XP, o nível e a meta de lazer desta conta. "
                        + "Os dados não poderão ser recuperados.");
        confirmation.getButtonTypes().setAll(ButtonType.CANCEL,
                new ButtonType("Continuar para confirmar", javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        Optional<ButtonType> answer = confirmation.showAndWait();
        if (answer.isEmpty() || answer.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
            return;
        }

        PasswordField password = new PasswordField();
        password.setPromptText("Digite a senha da conta");
        Dialog<String> passwordDialog = new Dialog<>();
        passwordDialog.setTitle("Confirmar exclusão");
        passwordDialog.setHeaderText("Digite sua senha para confirmar");
        passwordDialog.getDialogPane().setContent(password);
        ButtonType confirm = new ButtonType("Excluir conta", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        passwordDialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        passwordDialog.setResultConverter(button -> button == confirm ? password.getText() : null);
        Optional<String> submittedPassword = passwordDialog.showAndWait();
        password.clear();
        if (submittedPassword.isEmpty()) {
            return;
        }

        try {
            if (!authService.deleteAccount(currentUser, submittedPassword.get())) {
                showError("Senha incorreta", new IllegalArgumentException(
                        "A conta não foi excluída. Confira sua senha e tente novamente."));
                return;
            }
            currentUser.close();
            currentUser = null;
            showLogin();
            Alert deleted = new Alert(Alert.AlertType.INFORMATION);
            deleted.setTitle(APP_TITLE);
            deleted.setHeaderText("Conta excluída");
            deleted.setContentText("A conta e os dados associados foram removidos deste dispositivo.");
            deleted.showAndWait();
        } catch (SQLException exception) {
            showError("Não foi possível excluir a conta", exception);
        }
    }

    private String normalizedPicture(String picture) {
        return picture == null || picture.isBlank() ? "✍️" : picture;
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() {
        stopDeadlineTicker();
        if (currentUser != null) {
            currentUser.close();
        }
    }
}
