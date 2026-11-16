// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Logger;

/** Opt-in only. Never sends filesystem paths, exceptions, player data, or webhook credentials. */
final class DiscordNotifier {
    /** Discord rejects message content longer than 2000 characters. */
    static final int MAX_CONTENT = 1900;
    private final Logger log;
    /** Created on first use, so a server without Discord notifications never starts HTTP client threads. */
    private HttpClient client;
    private boolean closed;
    DiscordNotifier(Logger log) { this.log = log; }
    synchronized void send(PluginSettings settings, String message) {
        if (closed || !settings.discordEnabled()) return;
        try {
            if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request = HttpRequest.newBuilder(settings.webhook()).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(message)))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
                if (error != null || response.statusCode() / 100 != 2)
                    log.warning(Messages.text(settings.language(), "discord.failed"));
                return null;
            });
        } catch (RuntimeException | LinkageError failure) {
            // A notification must never fail the backup job that reports through it.
            log.warning(Messages.text(settings.language(), "discord.failed"));
        }
    }
    /** Stops the client's threads when the plugin is disabled; requests still in flight are abandoned. */
    synchronized void close() {
        closed = true;
        if (client != null) client.shutdownNow();
        client = null;
    }

    /** Complete JSON string encoding (RFC 8259): every control character is escaped, never dropped or passed raw. */
    static String payload(String message) {
        String content = "VaultBackup Pro: " + (message == null ? "" : message);
        if (content.length() > MAX_CONTENT) {
            int end = MAX_CONTENT;
            if (Character.isHighSurrogate(content.charAt(end - 1))) end--;
            content = content.substring(0, end) + "…";
        }
        StringBuilder json = new StringBuilder(content.length() + 64).append("{\"content\":\"");
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        return json.append("\",\"allowed_mentions\":{\"parse\":[]}}").toString();
    }
}
