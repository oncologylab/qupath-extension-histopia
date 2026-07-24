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
                        manifest, Set.of("section.ndpi")).path());
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

    @Test
    void verifiesSchemaTwoAnnotationChecksum() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{}");
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": 2,
                  "format": "histopia-qupath-bundle",
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson",
                    "semantic_annotations_sha256":
                      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                    "semantic_annotation_classes": 5,
                    "semantic_annotation_regions": 120,
                    "semantic_patch_count": 450
                  }]
                }
                """);

        var artifact = HistopiaBundle.findSemanticAnnotations(
                manifest, Set.of("section.ndpi"));

        assertEquals(annotations, artifact.path());
        assertEquals(5, artifact.classCount());
        assertEquals(120, artifact.regionCount());
        assertEquals(450, artifact.patchCount());
    }

    @Test
    void rejectsChangedSchemaTwoAnnotations() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{\"changed\":true}");
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": 2,
                  "format": "histopia-qupath-bundle",
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson",
                    "semantic_annotations_sha256":
                      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
                  }]
                }
                """);

        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));
    }

    @Test
    void rejectsSchemaTwoAnnotationWithoutChecksum() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{}");
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": 2,
                  "format": "histopia-qupath-bundle",
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson"
                  }]
                }
                """);

        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));
    }

    @Test
    void readsAvailableAndSelectedSemanticClusters() throws IOException {
        Files.writeString(
                directory.resolve("semantic_result.json"),
                """
                {
                  "cluster_counts": [5, 7, 9],
                  "selected_k": 7
                }
                """);

        var summary = HistopiaBundle.readSemanticSummary(directory);

        assertEquals(java.util.List.of(5, 7, 9), summary.clusterCounts());
        assertEquals(7, summary.selectedClusters());
    }
}
