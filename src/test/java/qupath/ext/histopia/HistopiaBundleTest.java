package qupath.ext.histopia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistopiaBundleTest {

    @TempDir
    Path directory;

    @Test
    void matchesCurrentSlideToSemanticAnnotations() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{}");
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "format": "histopia-qupath-bundle",
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson"
                  }]
                }
                """);

        assertEquals(
                annotations,
                HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));
    }

    @Test
    void rejectsMismatchedSlide() throws IOException {
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {"format":"histopia-qupath-bundle","slides":[]}
                """);

        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("different.ndpi")));
    }
}
