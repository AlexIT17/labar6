package ru.lr.lr6;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Получает страницу с аудиоплеером и находит прямую ссылку на аудиофайл.
 * Также поддерживает уже готовую прямую HTTP(S)-ссылку на аудиофайл.
 */
public final class AudioPageParser {

    private static final int PAGE_TIMEOUT_MILLIS = 15_000;
    private static final List<String> AUDIO_EXTENSIONS = List.of(
            ".mp3", ".wav", ".ogg", ".oga", ".m4a", ".flac", ".aac", ".opus", ".webm"
    );

    private AudioPageParser() {
        // Вспомогательный класс не предназначен для создания экземпляров.
    }

    /** Проверяет, что URI является абсолютным HTTP(S)-адресом. */
    public static URI validateHttpUri(URI uri) {
        Objects.requireNonNull(uri, "Адрес не должен быть null");

        String scheme = uri.getScheme();
        boolean supportedScheme = scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));

        if (!supportedScheme || !uri.isAbsolute() || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "Введите корректный адрес, начинающийся с http:// или https://"
            );
        }
        return uri;
    }

    /**
     * Возвращает прямой аудиоадрес. Сначала проверяет, не является ли введённый URL
     * уже ссылкой на аудиофайл. Иначе загружает HTML и ищет поддерживаемые варианты
     * разметки аудиоплеера.
     */
    public static URI findAudioUri(URI pageOrAudioUri) throws IOException {
        validateHttpUri(pageOrAudioUri);

        // Пользователь может вставить готовую ссылку на .mp3/.wav и т. п.
        if (isDirectAudioUri(pageOrAudioUri)) {
            return pageOrAudioUri;
        }

        final Document document;
        try {
            document = Jsoup.connect(pageOrAudioUri.toString())
                    .userAgent("LR6-MusicDownloader/1.0 (Java; Jsoup)")
                    .timeout(PAGE_TIMEOUT_MILLIS)
                    .followRedirects(true)
                    .get();
        } catch (HttpStatusException exception) {
            int status = exception.getStatusCode();
            if (status == 403) {
                throw new IOException(
                        "Сайт запретил автоматическое чтение страницы (HTTP 403). "
                        + "Это ограничение сайта, а не ошибка поиска .jp-play. "
                        + "Попробуйте прямую ссылку на аудиофайл, если сайт её предоставляет, "
                        + "или используйте другой источник с разрешённым скачиванием.",
                        exception
                );
            }
            if (status == 429) {
                throw new IOException(
                        "Сайт временно ограничил количество запросов (HTTP 429). "
                        + "Попробуйте позже или используйте прямую ссылку на аудиофайл.",
                        exception
                );
            }
            throw new IOException(
                    "Не удалось открыть страницу: сервер вернул HTTP " + status + ".",
                    exception
            );
        }

        // Основной селектор из исходной статьи.
        Element playerLink = document.selectFirst(".jp-play[href]");
        if (playerLink != null) {
            return createAbsoluteUri(playerLink, "href");
        }

        // Альтернативная разметка стандартного HTML5-аудиоплеера.
        Element audio = document.selectFirst("audio[src]");
        if (audio != null) {
            return createAbsoluteUri(audio, "src");
        }

        Element source = document.selectFirst("audio source[src], source[src]");
        if (source != null) {
            return createAbsoluteUri(source, "src");
        }

        // Ссылки, адрес которых явно заканчивается расширением аудиофайла.
        for (Element link : document.select("a[href]")) {
            String href = link.absUrl("href").trim();
            if (!href.isEmpty()) {
                try {
                    URI candidate = URI.create(href);
                    if (isDirectAudioUri(candidate)) {
                        return validateHttpUri(candidate);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Пропускаем некорректную ссылку и продолжаем поиск.
                }
            }
        }

        throw new IOException(
                "На странице не найдена ссылка на аудиофайл. "
                + "Проверены .jp-play[href], audio[src], source[src] и ссылки на аудиофайлы. "
                + "Возможно, сайт загружает аудио динамически или использует другой плеер."
        );
    }

    /** Определяет прямую ссылку по расширению в пути URL. */
    public static boolean isDirectAudioUri(URI uri) {
        String extension = getFileExtension(uri);
        return AUDIO_EXTENSIONS.contains(extension);
    }

    /** Извлекает расширение из пути URL; параметры запроса не учитываются. */
    public static String getFileExtension(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "";
        }

        int slashIndex = path.lastIndexOf('/');
        String fileName = path.substring(slashIndex + 1);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        String extension = fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }

    private static URI createAbsoluteUri(Element element, String attribute) throws IOException {
        String absoluteUrl = element.absUrl(attribute).trim();
        if (absoluteUrl.isEmpty()) {
            throw new IOException("У найденного элемента отсутствует адрес аудиофайла.");
        }

        try {
            URI audioUri = URI.create(absoluteUrl);
            return validateHttpUri(audioUri);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Страница содержит некорректную ссылку на аудиофайл.", exception);
        }
    }
}
