package ru.lr.lr6;

import javafx.concurrent.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;

/**
 * Фоновая задача поиска и скачивания аудиофайла.
 */
public final class AudioDownloadTask extends Task<Path> {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(20);

    private final URI pageUri;
    private final Path destination;

    /**
     * Создаёт задачу загрузки.
     */
    public AudioDownloadTask(URI pageUri, Path destination) {
        this.pageUri = AudioPageParser.validateHttpUri(pageUri);
        this.destination = Objects.requireNonNull(destination, "Путь сохранения не задан")
                .toAbsolutePath()
                .normalize();
    }

    /** Выполняет поиск аудиоссылки и сохранение файла в фоновом потоке. */
    @Override
    protected Path call() throws Exception {
        updateProgress(-1, 1);
        updateMessage("Ищу аудиофайл на странице...");

        URI audioUri = AudioPageParser.findAudioUri(pageUri);
        checkCancellation();

        updateMessage("Подключаюсь к аудиофайлу...");

        HttpRequest request = HttpRequest.newBuilder(audioUri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (compatible; LR6MusicDownloader/1.0)")
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream input = response.body()) {
            int statusCode = response.statusCode();
            if (statusCode == 403) {
                throw new IOException(
                        "Сервер запретил скачивание аудиофайла (HTTP 403). "
                        + "Ссылка может быть доступна только через сайт или иметь ограниченный доступ. "
                        + "Используйте источник, который разрешает прямое скачивание."
                );
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Сервер вернул ошибку HTTP " + statusCode + ".");
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("");
            if (contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")
                    || contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                throw new IOException(
                        "По указанному адресу получена веб-страница, а не аудиофайл. "
                        + "Проверьте, что вы вставили прямую ссылку на скачивание."
                );
            }

            String contentDisposition = response.headers()
                    .firstValue("Content-Disposition")
                    .orElse("");
            String extension = AudioFileFormat.detectExtension(
                    audioUri, contentType, contentDisposition, destination.getFileName().toString()
            );
            Path outputPath = AudioFileFormat.withExtension(destination, extension);
            updateMessage("Определён формат аудио: " + extension.substring(1).toUpperCase(java.util.Locale.ROOT));

            Path parent = outputPath.getParent();
            if (parent == null) {
                throw new IOException("Не удалось определить папку для сохранения файла.");
            }
            Files.createDirectories(parent);

            Path temporaryFile = Files.createTempFile(parent, "lr6-audio-", ".part");
            try {
                long contentLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1L);
                long downloaded = copyStream(input, temporaryFile, contentLength);

                if (downloaded == 0) {
                    throw new IOException("Сервер вернул пустой файл.");
                }

                checkCancellation();
               
                boolean sameSelectedPath = outputPath.equals(destination.toAbsolutePath().normalize());
                if (!sameSelectedPath && Files.exists(outputPath)) {
                    throw new FileAlreadyExistsException(
                            outputPath.toString(), null,
                            "Файл с правильным расширением уже существует. "
                                    + "Выберите другое имя, чтобы не перезаписать его."
                    );
                }
                moveCompletedFile(temporaryFile, outputPath, sameSelectedPath);
                updateProgress(1, 1);
                updateMessage("Скачивание завершено.");
                return outputPath;
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    /** Копирует сетевой поток в файл и обновляет индикатор выполнения. */
    private long copyStream(InputStream input, Path temporaryFile, long contentLength)
            throws IOException, InterruptedException {
        long downloaded = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (OutputStream output = Files.newOutputStream(temporaryFile)) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                checkCancellation();
                output.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (contentLength > 0) {
                    updateProgress(Math.min(downloaded, contentLength), contentLength);
                    updateMessage(String.format(
                            "Скачано %.1f из %.1f КБ",
                            downloaded / 1024.0,
                            contentLength / 1024.0
                    ));
                } else {
                    updateProgress(-1, 1);
                    updateMessage(String.format(
                            "Скачано %.1f КБ",
                            downloaded / 1024.0
                    ));
                }
            }
        } catch (InterruptedException exception) {
            throw exception;
        } catch (IOException exception) {
            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Загрузка была отменена.");
            }
            throw exception;
        }

        return downloaded;
    }

    /** Проверяет отмену задачи между операциями чтения и записи. */
    private void checkCancellation() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Загрузка отменена пользователем.");
        }
    }

    /** Перемещает временный файл на итоговое место. */
    private static void moveCompletedFile(Path source, Path target, boolean replaceExisting)
            throws IOException {
        try {
            if (replaceExisting) {
                Files.move(source, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }
}
