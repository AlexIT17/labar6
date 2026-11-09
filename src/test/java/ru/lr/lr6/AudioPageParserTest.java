package ru.lr.lr6;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioPageParserTest {

    @Test
    void acceptsHttpAddress() {
        URI uri = URI.create("http://example.com/song");
        assertEquals(uri, AudioPageParser.validateHttpUri(uri));
    }

    @Test
    void acceptsHttpsAddress() {
        URI uri = URI.create("https://example.com/song");
        assertEquals(uri, AudioPageParser.validateHttpUri(uri));
    }

    @Test
    void rejectsFtpAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioPageParser.validateHttpUri(URI.create("ftp://example.com/song")));
    }

    @Test
    void rejectsAddressWithoutHost() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioPageParser.validateHttpUri(URI.create("https:/song")));
    }

    @Test
    void recognizesDirectMp3UrlWithQueryParameters() {
        assertEquals(true, AudioPageParser.isDirectAudioUri(
                URI.create("https://cdn.example.com/audio/track.mp3?token=abc")));
    }

    @Test
    void doesNotRecognizeHtmlPageAsDirectAudioUrl() {
        assertEquals(false, AudioPageParser.isDirectAudioUri(
                URI.create("https://example.com/music-page")));
    }

    @Test
    void extractsMp3Extension() {
        assertEquals(".mp3", AudioPageParser.getFileExtension(
                URI.create("https://example.com/music/track.MP3")));
    }

    @Test
    void returnsEmptyExtensionWhenItIsMissing() {
        assertEquals("", AudioPageParser.getFileExtension(
                URI.create("https://example.com/music/track")));
    }

    @Test
    void detectsOggExtensionFromContentType() {
        assertEquals(".ogg", AudioFileFormat.detectExtension(
                URI.create("https://cdn.example.com/download?id=123"),
                "audio/ogg; codecs=opus",
                "",
                "music.mp3"
        ));
    }

    @Test
    void detectsMp3ExtensionFromUrlWhenHeadersAreGeneric() {
        assertEquals(".mp3", AudioFileFormat.detectExtension(
                URI.create("https://cdn.example.com/audio/track.mp3?token=abc"),
                "application/octet-stream",
                "",
                "music.ogg"
        ));
    }

    @Test
    void detectsExtensionFromContentDisposition() {
        assertEquals(".flac", AudioFileFormat.extensionFromContentDisposition(
                "attachment; filename=\"live-recording.flac\""
        ));
    }

    @Test
    void detectsExtensionFromUtf8ContentDisposition() {
        assertEquals(".ogg", AudioFileFormat.extensionFromContentDisposition(
                "attachment; filename*=UTF-8''recording%20name.ogg"
        ));
    }

    @Test
    void replacesWrongAudioExtensionInSelectedPath() {
        assertEquals(Path.of("C:/Music/song.ogg"), AudioFileFormat.withExtension(
                Path.of("C:/Music/song.mp3"), ".ogg"
        ));
    }

    @Test
    void addsExtensionWhenSelectedPathHasNoExtension() {
        assertEquals(Path.of("C:/Music/my-song.wav"), AudioFileFormat.withExtension(
                Path.of("C:/Music/my-song"), ".wav"
        ));
    }

    @Test
    void detectsOggExtensionFromDirectOggUrl() {
        assertEquals(".ogg", AudioFileFormat.detectExtension(
                URI.create("https://upload.example.com/Song.ogg"),
                "application/octet-stream",
                "",
                "music.mp3"
        ));
    }
}
