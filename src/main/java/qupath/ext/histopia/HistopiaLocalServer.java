package qupath.ext.histopia;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HistopiaLocalServer {

    private static HttpServer activeServer;
    private static ExecutorService activeExecutor;

    private HistopiaLocalServer() {
    }

    static synchronized URI serve(Path root) throws IOException {
        stop();
        var realRoot = root.toAbsolutePath().normalize().toRealPath();
        var server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        var executor = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "histopia-review-http");
            thread.setDaemon(true);
            return thread;
        });
        server.createContext("/", exchange -> respond(exchange, realRoot));
        server.setExecutor(executor);
        server.start();
        activeServer = server;
        activeExecutor = executor;
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    static synchronized void stop() {
        if (activeServer != null)
            activeServer.stop(0);
        if (activeExecutor != null)
            activeExecutor.shutdownNow();
        activeServer = null;
        activeExecutor = null;
    }

    private static void respond(HttpExchange exchange, Path root) throws IOException {
        try (exchange) {
            var method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            var requestPath = exchange.getRequestURI().getPath();
            var relative = requestPath == null || requestPath.equals("/")
                    ? "index.html"
                    : requestPath.substring(1);
            var candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || !Files.exists(candidate)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (Files.isDirectory(candidate))
                candidate = candidate.resolve("index.html");
            if (!Files.isRegularFile(candidate)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            var real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType(real));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            var size = Files.size(real);
            exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : size);
            if (!"HEAD".equals(method)) {
                try (var input = Files.newInputStream(real);
                        var output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            }
        }
    }

    private static String contentType(Path path) throws IOException {
        var detected = Files.probeContentType(path);
        if (detected != null)
            return detected;
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".js"))
            return "text/javascript; charset=utf-8";
        if (name.endsWith(".json"))
            return "application/json; charset=utf-8";
        if (name.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (name.endsWith(".html"))
            return "text/html; charset=utf-8";
        if (name.endsWith(".webp"))
            return "image/webp";
        return "application/octet-stream";
    }
}
