package ru.lr.lr6;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определяет расширение аудиофайла по HTTP-заголовкам и адресу загрузки.
 * При сохранении исправляет расширение выбранного пользователем имени файла.
 */
public final class AudioFileFormat {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".ogg", ".oga", ".m4a", ".flac",
            ".aac", ".opus", ".webm"
    );

    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/mp3", ".mp3"),
            Map.entry("audio/wav", ".wav"),
            Map.entry("audio/wave", ".wav"),
            Map.entry("audio/x-wav", ".wav"),
            Map.entry("audio/vnd.wave", ".wav"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("application/ogg", ".ogg"),
            Map.entry("audio/x-m4a", ".m4a"),
            Map.entry("audio/mp4", ".m4a"),
            Map.entry("audio/flac", ".flac"),
            Map.entry("audio/x-flac", ".flac"),
            Map.entry("audio/aac", ".aac"),
            Map.entry("audio/aacp", ".aac"),
            Map.entry("audio/opus", ".opus"),
            Map.entry("audio/webm", ".webm")
    );

    private static final Pattern EXTENDED_FILENAME = Pattern.compile(
            "filename\\*\\s*=\\s*UTF-8'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REGULAR_FILENAME = Pattern.compile(
            "filename\\s*=\\s*\\\"?([^;\\\"]+)\\\"?", Pattern.CASE_INSENSITIVE
    );

    private AudioFileFormat() {
        // Utility class.
    }

    /**
     * Определяет расширение по фактическому Content-Type, Content-Disposition,
     * URL и, в последнюю очередь, исходному имени, выбранному пользователем.
     */
    public static String detectExtension(
            URI audioUri,
            String contentType,
            String contentDisposition,
            String fallbackFileName
    ) {
        String mimeExtension = extensionFromContentType(contentType);
        if (!mimeExtension.isEmpty()) {
            return mimeExtension;
        }

        String dispositionExtension = extensionFromContentDisposition(contentDisposition);
        if (!dispositionExtension.isEmpty()) {
            return dispositionExtension;
        }

        String urlExtension = AudioPageParser.getFileExtension(audioUri);
        if (SUPPORTED_EXTENSIONS.contains(urlExtension)) {
            return urlExtension;
        }

        String fallbackExtension = extensionFromFileName(fallbackFileName);
        return fallbackExtension.isEmpty() ? ".mp3" : fallbackExtension;
    }

    /** Возвращает расширение по MIME-типу, например audio/ogg -> .ogg. */
    public static String extensionFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }

        String mimeType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return MIME_EXTENSIONS.getOrDefault(mimeType, "");
    }

    /** Извлекает расширение из имени файла в заголовке Content-Disposition. */
    public static String extensionFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return "";
        }

        String fileName = extractDispositionFileName(contentDisposition);
        return extensionFromFileName(fileName);
    }

    /**
     * Возвращает путь с правильным расширением. Уже имеющееся поддерживаемое
     * аудиорасширение заменяется, неизвестное расширение сохраняется как часть имени.
     */
    public static Path withExtension(Path selectedPath, String extension) {
        if (selectedPath == null || selectedPath.getFileName() == null) {
            throw new IllegalArgumentException("Не задано имя файла для сохранения.");
        }
        if (extension == null || !SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Неподдерживаемое расширение аудиофайла: " + extension);
        }

        Path parent = selectedPath.getParent();
        String fileName = selectedPath.getFileName().toString();
        String existingExtension = extensionFromFileName(fileName);
        String baseName = existingExtension.isEmpty()
                ? fileName
                : fileName.substring(0, fileName.length() - existingExtension.length());

        if (baseName.isBlank()) {
            baseName = "music";
        }

        Path corrected = Path.of(baseName + extension.toLowerCase(Locale.ROOT));
        return parent == null ? corrected : parent.resolve(corrected);
    }

    /** Проверяет, относится ли расширение к поддерживаемым аудиоформатам. */
    public static boolean isSupportedExtension(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static String extensionFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        String cleanName = fileName.trim();
        int slash = Math.max(cleanName.lastIndexOf('/'), cleanName.lastIndexOf('\\'));
        cleanName = cleanName.substring(slash + 1);
        int dot = cleanName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }

        String extension = cleanName.substring(dot).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension) ? extension : "";
    }

    private static String extractDispositionFileName(String header) {
        Matcher extendedMatcher = EXTENDED_FILENAME.matcher(header);
        if (extendedMatcher.find()) {
            String encodedName = extendedMatcher.group(1).trim().replace("\"", "");
            try {
                return URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // Если кодировка заголовка некорректна, проверим обычный filename.
            }
        }

        Matcher regularMatcher = REGULAR_FILENAME.matcher(header);
        if (regularMatcher.find()) {
            return regularMatcher.group(1).trim();
        }
        return "";
    }
}
