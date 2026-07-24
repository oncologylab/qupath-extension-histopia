package qupath.ext.histopia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistopiaLocalServerTest {

    @TempDir
    Path directory;

    @AfterEach
    void stopServer() {
        HistopiaLocalServer.stop();
    }

    @Test
    void servesViewerAssetsOnlyFromConfiguredRoot() throws Exception {
        Files.createDirectories(directory.resolve("histopia"));
        Files.writeString(directory.resolve("histopia/index.html"), "semantic viewer");
        var outside = Files.createTempFile(directory.getParent(), "outside-", ".txt");
        Files.writeString(outside, "private");
        Files.createSymbolicLink(directory.resolve("escape.txt"), outside);
        try {
            var root = HistopiaLocalServer.serve(directory);

            assertEquals(
                    "semantic viewer",
                    new String(
                            URI.create(root + "histopia/index.html").toURL()
                                    .openStream()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
            assertEquals(404, responseCode(root.resolve("escape.txt")));
            assertEquals(404, responseCode(root.resolve("missing.txt")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private static int responseCode(URI uri) throws Exception {
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
