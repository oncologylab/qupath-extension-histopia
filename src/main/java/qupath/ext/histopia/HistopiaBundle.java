package qupath.ext.histopia;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class HistopiaBundle {

    record SemanticSummary(List<Integer> clusterCounts, int selectedClusters) {
        SemanticSummary {
            clusterCounts = List.copyOf(clusterCounts);
            if (clusterCounts.isEmpty() || !clusterCounts.contains(selectedClusters))
                throw new IllegalArgumentException("Selected K is not available");
        }
    }

    record SemanticArtifact(
            Path path,
            int classCount,
            int regionCount,
            int patchCount) {
    }

    private HistopiaBundle() {
    }

    static SemanticSummary readSemanticSummary(Path semanticRun) throws IOException {
        var result = semanticRun.resolve("semantic_result.json");
        try (var reader = Files.newBufferedReader(result)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var clusterCounts = new ArrayList<Integer>();
            for (var element : root.getAsJsonArray("cluster_counts"))
                clusterCounts.add(element.getAsInt());
            var selected = root.has("selected_k")
                    ? root.get("selected_k").getAsInt()
                    : root.get("primary_clusters").getAsInt();
            return new SemanticSummary(clusterCounts, selected);
        } catch (RuntimeException error) {
            throw new IOException("Semantic result is malformed: " + result, error);
        }
    }

    static SemanticArtifact findSemanticAnnotations(Path manifest, Set<String> imageNames)
            throws IOException {
        var manifestParent = manifest.toAbsolutePath().normalize().getParent();
        if (manifestParent == null)
            throw new IOException("Bundle manifest has no parent directory");
        SemanticArtifact match = null;
        try (var reader = Files.newBufferedReader(manifest)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"histopia-qupath-bundle".equals(root.get("format").getAsString()))
                throw new IOException("Selected JSON is not a Histopia QuPath bundle");
            var schemaVersion = root.has("schema_version")
                    ? root.get("schema_version").getAsInt()
                    : 1;
            for (var element : root.getAsJsonArray("slides")) {
                var slide = element.getAsJsonObject();
                var id = slide.get("id").getAsString();
                if (!imageNames.contains(id) || !slide.has("semantic_annotations"))
                    continue;
                var relative = slide.get("semantic_annotations").getAsString();
                var path = manifestParent.resolve(relative).normalize();
                if (!path.startsWith(manifestParent))
                    throw new IOException("Bundle annotation path escapes its directory");
                if (!Files.isRegularFile(path))
                    throw new IOException("Bundle annotation file is missing: " + relative);
                if (schemaVersion >= 2 && !slide.has("semantic_annotations_sha256"))
                    throw new IOException(
                            "Schema-2 bundle annotation has no checksum: " + relative);
                if (slide.has("semantic_annotations_sha256")) {
                    var expected = slide.get("semantic_annotations_sha256").getAsString();
                    var actual = sha256(path);
                    if (!actual.equalsIgnoreCase(expected))
                        throw new IOException(
                                "Bundle annotation checksum does not match: " + relative);
                }
                if (match != null)
                    throw new IOException(
                            "Multiple bundle slides match the open image; use unique image names");
                match = new SemanticArtifact(
                        path,
                        optionalInt(slide, "semantic_annotation_classes"),
                        optionalInt(slide, "semantic_annotation_regions"),
                        optionalInt(slide, "semantic_patch_count"));
            }
        } catch (RuntimeException error) {
            throw new IOException("Histopia bundle manifest is malformed", error);
        }
        if (match == null)
            throw new IOException(
                    "The open image does not match a semantic slide in this bundle");
        return match;
    }

    private static int optionalInt(com.google.gson.JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsInt() : -1;
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        try (InputStream stream = Files.newInputStream(path)) {
            var buffer = new byte[1024 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > 0)
                    digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
