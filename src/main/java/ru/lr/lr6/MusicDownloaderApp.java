package ru.lr.lr6;

import javafx.application.Application;
import javafx.concurrent.WorkerStateEvent;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Главное окно приложения для поиска и скачивания аудиофайла.
 */
public class MusicDownloaderApp extends Application {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audio-download-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final TextField pageUrlField = new TextField();
    private final Button downloadButton = new Button("Найти и скачать");
    private final Button cancelButton = new Button("Отмена");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Введите адрес страницы с плеером или прямую ссылку на аудиофайл.");
    private final TextArea logArea = new TextArea();

    private AudioDownloadTask activeTask;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Лабораторная работа № 6 — Качаем музыку");

        pageUrlField.setPromptText("https://example.com/song-page или https://example.com/music.mp3");
        pageUrlField.setPrefWidth(560);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(22);

        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(8);

        cancelButton.setDisable(true);
        downloadButton.setDefaultButton(true);
        downloadButton.setOnAction(event -> chooseDestinationAndDownload(stage));
        cancelButton.setOnAction(event -> cancelDownload());

        HBox buttons = new HBox(10, downloadButton, cancelButton);
        VBox root = new VBox(12,
                new Label("Ссылка на страницу с композицией или на аудиофайл:"),
                pageUrlField,
                buttons,
                new Label("Ход загрузки:"),
                progressBar,
                statusLabel,
                new Label("Журнал работы:"),
                logArea
        );
        root.setPadding(new Insets(18));

        stage.setScene(new Scene(root, 680, 420));
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        stage.setOnCloseRequest(event -> shutdownExecutor());
        stage.show();

        appendLog("Приложение запущено. Принимаются прямые ссылки на аудио и страницы с .jp-play[href], audio[src] или source[src].");
    }

    /** Выбирает итоговый файл и запускает задачу в отдельном потоке. */
    private void chooseDestinationAndDownload(Stage stage) {
        String address = pageUrlField.getText().trim();
        if (address.isEmpty()) {
            showError("Не указан адрес", "Введите адрес страницы с плеером или прямую ссылку на аудиофайл.");
            return;
        }

        final URI pageUri;
        try {
            pageUri = AudioPageParser.validateHttpUri(URI.create(address));
        } catch (IllegalArgumentException exception) {
            showError("Некорректный адрес", exception.getMessage());
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить аудиофайл");
        String suggestedExtension = AudioPageParser.getFileExtension(pageUri);
        if (!AudioFileFormat.isSupportedExtension(suggestedExtension)) {
            suggestedExtension = ".mp3";
        }
        chooser.setInitialFileName("music" + suggestedExtension);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Аудиофайлы", "*.mp3", "*.wav", "*.ogg", "*.oga", "*.m4a",
                        "*.flac", "*.aac", "*.opus", "*.webm"
                )
        );
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Все файлы", "*.*")
        );

        File selectedFile = chooser.showSaveDialog(stage);
        if (selectedFile == null) {
            appendLog("Выбор места сохранения отменён.");
            return;
        }

        Path destination = selectedFile.toPath();
        if (Files.exists(destination)) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Файл уже существует. Заменить его?",
                    ButtonType.YES, ButtonType.NO);
            confirmation.setHeaderText("Подтверждение перезаписи");
            if (confirmation.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                return;
            }
        }

        startDownload(pageUri, destination);
    }

    /** Создаёт задачу загрузки и назначает обработчики её состояний. */
    private void startDownload(URI pageUri, Path destination) {
        activeTask = new AudioDownloadTask(pageUri, destination);

        downloadButton.setDisable(true);
        cancelButton.setDisable(false);
        pageUrlField.setDisable(true);
        progressBar.progressProperty().bind(activeTask.progressProperty());
        statusLabel.textProperty().bind(activeTask.messageProperty());

        activeTask.messageProperty().addListener((observable, oldMessage, newMessage) -> {
            if (newMessage != null && !newMessage.isBlank()) {
                appendLog(newMessage);
            }
        });

        activeTask.setOnSucceeded(this::handleSuccess);
        activeTask.setOnFailed(this::handleFailure);
        activeTask.setOnCancelled(this::handleCancelled);

        appendLog("Начата обработка адреса: " + pageUri);
        appendLog("Расширение итогового файла будет определено по HTTP-заголовкам и адресу аудио.");
        executor.execute(activeTask);
    }

    /** Обработчик успешного завершения загрузки. */
    private void handleSuccess(WorkerStateEvent event) {
        Path result = activeTask.getValue();
        resetControls();
        statusLabel.setText("Готово: " + result.getFileName());
        appendLog("Файл сохранён: " + result);
    }

    /** Обработчик ошибки загрузки. */
    private void handleFailure(WorkerStateEvent event) {
        Throwable error = activeTask.getException();
        resetControls();

        String message = error == null || error.getMessage() == null
                ? "Неизвестная ошибка."
                : error.getMessage();
        statusLabel.setText("Ошибка загрузки.");
        appendLog("Ошибка: " + message);
        showError("Не удалось скачать файл", message);
    }

    /** Обработчик отмены загрузки пользователем. */
    private void handleCancelled(WorkerStateEvent event) {
        resetControls();
        statusLabel.setText("Загрузка отменена.");
        appendLog("Загрузка отменена пользователем.");
    }

    /** Возвращает элементы интерфейса в исходное состояние. */
    private void resetControls() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        downloadButton.setDisable(false);
        cancelButton.setDisable(true);
        pageUrlField.setDisable(false);
        activeTask = null;
    }

    /** Отменяет текущую задачу, если загрузка выполняется. */
    private void cancelDownload() {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
    }

    private void appendLog(String message) {
        logArea.appendText(message + System.lineSeparator());
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Ошибка");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void shutdownExecutor() {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        executor.shutdownNow();
    }

    @Override
    public void stop() {
        shutdownExecutor();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
