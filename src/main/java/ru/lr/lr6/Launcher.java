package ru.lr.lr6;

import javafx.application.Application;

/**
 * Точка входа приложения.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MusicDownloaderApp.class, args);
    }
}
