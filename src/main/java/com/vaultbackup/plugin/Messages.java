// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Keyed UTF-8 message bundles. Filenames, backup IDs and command literals stay unchanged. */
final class Messages {
    static final String DEFAULT_LANGUAGE = "ja_JP";
    private static final Map<String, Properties> BUNDLES = Map.of("ja_JP", load("ja_JP"), "en_US", load("en_US"));
    private Messages() {}
    private static Properties load(String language) {
        try (InputStream in = Messages.class.getResourceAsStream("/messages/" + language + ".properties")) {
            if (in == null) throw new IllegalStateException("Missing message bundle: " + language);
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean supported(String language) { return language != null && BUNDLES.containsKey(language); }
    static Set<String> keys(String language) { return BUNDLES.get(language).stringPropertyNames(); }
    static String text(String language, String key, Object... arguments) {
        String value = BUNDLES.getOrDefault(language == null ? DEFAULT_LANGUAGE : language, BUNDLES.get(DEFAULT_LANGUAGE)).getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing message key: " + key);
        // Replace only placeholders from the template; argument data is never interpreted as a template.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)}").matcher(value);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            m.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(index < arguments.length ? String.valueOf(arguments[index]) : m.group()));
        }
        return m.appendTail(result).toString();
    }
    static String phase(String language, String raw) {
        if (raw == null || raw.isBlank()) return text(language, "progress.ready");
        return switch (raw) {
            case "Ready" -> text(language, "progress.ready");
            case "Starting" -> text(language, "progress.starting");
            case "Scanning server files" -> text(language, "progress.scanning");
            case "Verifying new archive" -> text(language, "progress.verifynew");
            default -> {
                String[][] phases = {{"Backing up ", "progress.backup"}, {"Backup complete: ", "progress.complete"},
                        {"Retention deleted ", "progress.retention"}, {"Verifying ", "progress.verify"}};
                String translated = null;
                for (String[] phase : phases) if (raw.startsWith(phase[0])) {
                    translated = text(language, phase[1], raw.substring(phase[0].length())); break;
                }
                yield translated == null ? text(language, "progress.working") : translated;
            }
        };
    }
}
