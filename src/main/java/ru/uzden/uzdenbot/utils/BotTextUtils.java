package ru.uzden.uzdenbot.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class BotTextUtils {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private BotTextUtils() {
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String formatDate(LocalDateTime dt) {
        return dt == null ? "-" : dt.format(DT_FMT);
    }
}
