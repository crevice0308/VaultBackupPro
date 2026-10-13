// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Opt-in only. Never sends filesystem paths, exceptions, player data, or webhook credentials. */
final class DiscordNotifier {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Logger log;
    DiscordNotifier(Logger log) { this.log = log; }
    void send(PluginSettings settings, String message) {
        if (!settings.discordEnabled()) return;
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        HttpRequest request = HttpRequest.newBuilder(settings.webhook()).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"VaultBackup Pro: " + safe + "\",\"allowed_mentions\":{\"parse\":[]}}"))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
            if (error != null || response.statusCode() / 100 != 2)
                log.warning(Messages.text(settings.language(), "discord.failed"));
            return null;
        });
    }
}
