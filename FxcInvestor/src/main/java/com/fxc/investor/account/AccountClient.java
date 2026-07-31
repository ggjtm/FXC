package com.fxc.investor.account;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens this agent's account at FxcBroker (docs/stories/004).
 *
 * <p>An agent that shares an account with every other agent has no P&amp;L of its own — the console's
 * per-account curve becomes a blend of whatever else is trading. So each one asks the broker for an
 * account under a stable client id and trades only that.
 *
 * <p><b>Why this is the one thing an investor does over REST.</b> Everything else it says to the
 * broker is OFX, which has no account-opening message set; inventing a third custom one
 * ({@code FXCACCT…}) for a call each agent makes exactly once, at startup, buys nothing over the
 * broker's existing HTTP surface. The trade is stated plainly in DESIGN §4.4: a second protocol for a
 * single call.
 *
 * <p><b>Query parameters in, JSON out</b>, matching the broker's other POSTs — the repo has a JSON
 * writer and deliberately no parser. The response is one flat object written by {@code Json}, so the
 * account number is pulled out with a regex rather than by parsing; anything more would need the
 * dependency the repo has spent some effort avoiding.
 *
 * <p><b>Never fatal.</b> A broker with account opening switched off, or no console at all, means the
 * agent falls back to its configured account. Refusing to trade because it could not have a *private*
 * account would be a worse outcome than trading in a shared one.
 */
public final class AccountClient {

    /** The response is our own single-line JSON, so this is a read of a known shape, not parsing. */
    private static final Pattern ACCOUNT = Pattern.compile("\"account\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OPENED = Pattern.compile("\"opened\"\\s*:\\s*(true|false)");

    private final String consoleUrl;
    private final HttpClient http;
    private final Duration timeout;

    public AccountClient(String consoleUrl) {
        this(consoleUrl, Duration.ofSeconds(5));
    }

    public AccountClient(String consoleUrl, Duration timeout) {
        this.consoleUrl = consoleUrl.endsWith("/")
                ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Ask for this client's account, opening one if it does not have it yet.
     *
     * @return the account number, or empty if the broker declined or could not be reached — the
     *         caller falls back to its configured account and says so.
     */
    public Optional<Result> open(String clientId, String ownerName) {
        String url = consoleUrl + "/api/accounts?clientId=" + encode(clientId)
                + (ownerName == null || ownerName.isBlank() ? "" : "&ownerName=" + encode(ownerName));
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                System.out.println("[account] broker declined to open an account (HTTP "
                        + response.statusCode() + "): " + response.body().strip());
                return Optional.empty();
            }
            Matcher account = ACCOUNT.matcher(response.body());
            if (!account.find()) {
                System.out.println("[account] broker's reply carried no account: " + response.body());
                return Optional.empty();
            }
            Matcher opened = OPENED.matcher(response.body());
            return Optional.of(new Result(account.group(1),
                    opened.find() && Boolean.parseBoolean(opened.group(1))));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[account] could not reach the broker console at " + consoleUrl
                    + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The account this agent trades, and whether this call is what created it. */
    public record Result(String account, boolean opened) {
    }
}
