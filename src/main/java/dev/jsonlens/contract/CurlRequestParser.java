package dev.jsonlens.contract;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses common cURL request syntax without executing the command. */
public final class CurlRequestParser {
    public record Request(String method, String url, String path, String body) {}

    public Request parse(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Cole um comando cURL.");
        if (command.length() > 2_000_000) throw new IllegalArgumentException("Comando cURL acima de 2 MB; use uma entrada menor.");

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty() || !(tokens.getFirst().equalsIgnoreCase("curl")
            || tokens.getFirst().equalsIgnoreCase("curl.exe"))) {
            throw new IllegalArgumentException("O conteúdo deve começar com curl.");
        }

        String method = null;
        String url = null;
        String body = null;
        boolean forceGet = false;
        boolean head = false;

        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("-X") || token.equals("--request")) {
                method = argument(tokens, ++i, token).toUpperCase(Locale.ROOT);
            } else if (token.startsWith("--request=")) {
                method = token.substring("--request=".length()).toUpperCase(Locale.ROOT);
            } else if (token.equals("--url")) {
                url = argument(tokens, ++i, token);
            } else if (token.startsWith("--url=")) {
                url = token.substring("--url=".length());
            } else if (isDataOption(token)) {
                body = setBody(body, argument(tokens, ++i, token));
            } else if (isDataOptionWithValue(token)) {
                int equals = token.indexOf('=');
                body = setBody(body, token.substring(equals + 1));
            } else if (token.equals("--data-urlencode") || token.startsWith("--data-urlencode=")) {
                throw new IllegalArgumentException("Formulários --data-urlencode não são suportados; use um body JSON com --data ou --data-raw.");
            } else if (token.equals("--get") || token.equals("-G")) {
                forceGet = true;
            } else if (token.equals("--head") || token.equals("-I")) {
                head = true;
            } else if (token.startsWith("-X") && !token.startsWith("--") && token.length() > 2) {
                method = token.substring(2).toUpperCase(Locale.ROOT);
            } else if (token.startsWith("-d") && !token.startsWith("--") && token.length() > 2) {
                body = setBody(body, token.substring(2));
            } else if (isHttpUrl(token)) {
                url = token;
            }
        }

        if (url == null || url.isBlank()) throw new IllegalArgumentException("O comando cURL precisa conter uma URL HTTP ou HTTPS.");
        if (forceGet && body != null)
            throw new IllegalArgumentException("Com --get, os dados são enviados na query string, não como body JSON.");
        String path = requestPath(url);
        String resolvedMethod = method != null ? method : forceGet ? "GET" : body != null ? "POST" : head ? "HEAD" : "GET";
        return new Request(resolvedMethod, url, path, body);
    }

    private static String argument(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) throw new IllegalArgumentException("Falta o valor da opção " + option + ".");
        return tokens.get(index);
    }

    private static String setBody(String existing, String value) {
        if (existing != null) throw new IllegalArgumentException("Use apenas uma opção de body JSON no comando cURL.");
        return value;
    }

    private static boolean isDataOption(String token) {
        return token.equals("-d") || token.equals("--data") || token.equals("--data-raw")
            || token.equals("--data-binary") || token.equals("--data-ascii");
    }

    private static boolean isDataOptionWithValue(String token) {
        return token.startsWith("--data=") || token.startsWith("--data-raw=")
            || token.startsWith("--data-binary=") || token.startsWith("--data-ascii=");
    }

    private static boolean isHttpUrl(String token) {
        return token.startsWith("http://") || token.startsWith("https://");
    }

    private static String requestPath(String url) {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("A URL do cURL é inválida.", ex);
        }
        if (!uri.isAbsolute() || uri.getHost() == null
            || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("A URL do cURL precisa usar HTTP ou HTTPS.");
        }
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;

        for (int i = 0; i < command.length(); i++) {
            char current = command.charAt(i);
            if (quote == 0 && (current == '\\' || current == '`') && i + 1 < command.length()
                && (command.charAt(i + 1) == '\n' || command.charAt(i + 1) == '\r')) {
                addToken(tokens, token, started);
                started = false;
                if (command.charAt(i + 1) == '\r' && i + 2 < command.length() && command.charAt(i + 2) == '\n') i += 2;
                else i++;
                continue;
            }

            if (quote == '\'') {
                if (current == '\'') quote = 0;
                else token.append(current);
                started = true;
            } else if (quote == '"') {
                if (current == '"') quote = 0;
                else if (current == '\\' && i + 1 < command.length()
                    && (command.charAt(i + 1) == '"' || command.charAt(i + 1) == '\\')) {
                    token.append(command.charAt(++i));
                } else if (current == '`' && i + 1 < command.length() && command.charAt(i + 1) == '"') {
                    token.append(command.charAt(++i));
                } else token.append(current);
                started = true;
            } else if (current == '\'' || current == '"') {
                quote = current;
                started = true;
            } else if (Character.isWhitespace(current)) {
                addToken(tokens, token, started);
                started = false;
            } else {
                token.append(current);
                started = true;
            }
        }

        if (quote != 0) throw new IllegalArgumentException("O comando cURL contém aspas sem fechamento.");
        addToken(tokens, token, started);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token, boolean started) {
        if (started) tokens.add(token.toString());
        token.setLength(0);
    }
}
