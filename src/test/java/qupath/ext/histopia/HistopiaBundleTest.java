package qupath.ext.histopia;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
                    "semantic_annotations_bytes": 2,
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

    @Test
    void rejectsAnnotationSymlinkOutsideBundle() throws IOException {
        var outside = Files.createTempFile(directory.getParent(), "outside-", ".geojson");
        Files.writeString(outside, "{}");
        var annotations = directory.resolve("annotations/escape.geojson");
        Files.createDirectories(annotations.getParent());
        Files.createSymbolicLink(annotations, outside);
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "format": "histopia-qupath-bundle",
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/escape.geojson"
                  }]
                }
                """);
        try {
            assertThrows(
                    IOException.class,
                    () -> HistopiaBundle.findSemanticAnnotations(
                            manifest, Set.of("section.ndpi")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsCoerciveSchemaTypes() throws IOException {
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": "2",
                  "format": "histopia-qupath-bundle",
                  "slides": []
                }
                """);

        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));
    }

    @Test
    void requiresFingerprintBoundApprovalForSchemaThree() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{}");
        var manifest = directory.resolve("histopia-qupath.json");
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": 3,
                  "format": "histopia-qupath-bundle",
                  "semantic_fingerprint": "result-fingerprint",
                  "semantic_preflight_fingerprint": "preflight-fingerprint",
                  "semantic_approval": {
                    "fingerprint": "result-fingerprint",
                    "reviewer": "Reviewer",
                    "reviewed_at": "2026-07-24T18:30:00+00:00"
                  },
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson",
                    "semantic_annotations_sha256":
                      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                    "semantic_annotations_bytes": 2
                  }]
                }
                """);

        assertEquals(
                annotations,
                HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")).path());

        var changed = Files.readString(manifest)
                .replace(
                        "\"fingerprint\": \"result-fingerprint\"",
                        "\"fingerprint\": \"stale-fingerprint\"");
        Files.writeString(manifest, changed);
        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                    manifest, Set.of("section.ndpi")));
    }

    @Test
    void requiresFinalRegistrationApprovalForSchemaFour() throws IOException {
        var annotations = directory.resolve("annotations/001-section.geojson");
        Files.createDirectories(annotations.getParent());
        Files.writeString(annotations, "{}");
        var manifest = directory.resolve("histopia-qupath.json");
        var registrationSha =
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Files.writeString(
                manifest,
                """
                {
                  "schema_version": 4,
                  "format": "histopia-qupath-bundle",
                  "registration_sha256": "%s",
                  "registration_approval": {
                    "approval_sha256":
                      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "registration_result_sha256": "%s",
                    "order_fingerprint": "order-fingerprint",
                    "reviewer": "Reviewer",
                    "reviewed_at": "2026-07-24T18:00:00+00:00"
                  },
                  "semantic_fingerprint": "result-fingerprint",
                  "semantic_preflight_fingerprint": "preflight-fingerprint",
                  "semantic_approval": {
                    "fingerprint": "result-fingerprint",
                    "reviewer": "Reviewer",
                    "reviewed_at": "2026-07-24T18:30:00+00:00"
                  },
                  "slides": [{
                    "id": "section.ndpi",
                    "semantic_annotations": "annotations/001-section.geojson",
                    "semantic_annotations_sha256":
                      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                    "semantic_annotations_bytes": 2
                  }]
                }
                """.formatted(registrationSha, registrationSha));

        assertEquals(
                annotations,
                HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")).path());

        var original = Files.readString(manifest);
        Files.writeString(
                manifest,
                original.replace(
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "not-a-sha256"));
        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));

        Files.writeString(
                manifest,
                original.replace(
                        "\"registration_result_sha256\": \"%s\""
                                .formatted(registrationSha),
                        "\"registration_result_sha256\": "
                                + "\"cccccccccccccccccccccccccccccccc"
                                + "cccccccccccccccccccccccccccccccc\""));
        assertThrows(
                IOException.class,
                () -> HistopiaBundle.findSemanticAnnotations(
                        manifest, Set.of("section.ndpi")));
    }

    @Test
    void validatesExternalCurrentBundleWhenConfigured() throws IOException {
        var configured = System.getenv("HISTOPIA_VALIDATED_QUPATH_BUNDLE");
        assumeTrue(configured != null && !configured.isBlank());
        var manifest = Path.of(configured);
        var root = JsonParser.parseString(Files.readString(manifest))
                .getAsJsonObject();
        var first = root.getAsJsonArray("slides").get(0).getAsJsonObject();
        var id = first.get("id").getAsString();

        var artifact = HistopiaBundle.findSemanticAnnotations(
                manifest, Set.of(id));

        assertTrue(root.get("schema_version").getAsInt() >= 3);
        assertEquals(
                first.get("semantic_annotations_bytes").getAsLong(),
                Files.size(artifact.path()));
    }
}
