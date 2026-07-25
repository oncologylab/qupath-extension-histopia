package qupath.ext.histopia;

import com.google.gson.JsonObject;
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
        var manifestPath = manifest.toAbsolutePath().normalize().toRealPath();
        var manifestParent = manifestPath.getParent();
        if (manifestParent == null)
            throw new IOException("Bundle manifest has no parent directory");
        SemanticArtifact match = null;
        try (var reader = Files.newBufferedReader(manifestPath)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"histopia-qupath-bundle".equals(requiredString(root, "format")))
                throw new IOException("Selected JSON is not a Histopia QuPath bundle");
            var schemaVersion = root.has("schema_version")
                    ? requiredInteger(root, "schema_version")
                    : 1;
            if (schemaVersion < 1 || schemaVersion > 4)
                throw new IOException("Unsupported Histopia bundle schema");
            if (!root.has("slides") || !root.get("slides").isJsonArray())
                throw new IOException("Histopia bundle has no slide array");
            for (var element : root.getAsJsonArray("slides")) {
                if (!element.isJsonObject())
                    throw new IOException("Histopia bundle slides must be objects");
                var slide = element.getAsJsonObject();
                var id = requiredString(slide, "id");
                if (!imageNames.contains(id) || !slide.has("semantic_annotations"))
                    continue;
                if (schemaVersion >= 3)
                    validateSemanticApproval(root);
                if (schemaVersion >= 4)
                    validateRegistrationApproval(root);
                var relative = requiredString(slide, "semantic_annotations");
                var path = manifestParent.resolve(relative).normalize();
                if (!path.startsWith(manifestParent))
                    throw new IOException("Bundle annotation path escapes its directory");
                if (!Files.isRegularFile(path))
                    throw new IOException("Bundle annotation file is missing: " + relative);
                path = path.toRealPath();
                if (!path.startsWith(manifestParent))
                    throw new IOException(
                            "Bundle annotation symlink escapes its directory");
                if (schemaVersion >= 2 && !slide.has("semantic_annotations_sha256"))
                    throw new IOException(
                            "Bundle annotation has no checksum: " + relative);
                if (slide.has("semantic_annotations_sha256")) {
                    var expected = requiredString(
                            slide, "semantic_annotations_sha256");
                    if (!expected.matches("[0-9a-fA-F]{64}"))
                        throw new IOException(
                                "Bundle annotation checksum is malformed: " + relative);
                    var actual = sha256(path);
                    if (!actual.equalsIgnoreCase(expected))
                        throw new IOException(
                                "Bundle annotation checksum does not match: " + relative);
                }
                if (schemaVersion >= 2) {
                    var expectedBytes = requiredNonnegativeLong(
                            slide, "semantic_annotations_bytes");
                    if (Files.size(path) != expectedBytes)
                        throw new IOException(
                                "Bundle annotation byte size does not match: " + relative);
                }
                if (match != null)
                    throw new IOException(
                            "Multiple bundle slides match the open image; use unique image names");
                match = new SemanticArtifact(
                        path,
                        optionalNonnegativeInteger(
                                slide, "semantic_annotation_classes"),
                        optionalNonnegativeInteger(
                                slide, "semantic_annotation_regions"),
                        optionalNonnegativeInteger(slide, "semantic_patch_count"));
            }
        } catch (RuntimeException error) {
            throw new IOException("Histopia bundle manifest is malformed", error);
        }
        if (match == null)
            throw new IOException(
                    "The open image does not match a semantic slide in this bundle");
        return match;
    }

    private static void validateSemanticApproval(JsonObject root) throws IOException {
        var fingerprint = requiredString(root, "semantic_fingerprint");
        requiredString(root, "semantic_preflight_fingerprint");
        if (!root.has("semantic_approval")
                || !root.get("semantic_approval").isJsonObject())
            throw new IOException("Schema-3 bundle has no semantic approval");
        var approval = root.getAsJsonObject("semantic_approval");
        if (!fingerprint.equals(requiredString(approval, "fingerprint")))
            throw new IOException("Schema-3 semantic approval fingerprint is stale");
        requiredString(approval, "reviewer");
        if (approval.has("reviewed_at")
                && !approval.get("reviewed_at").isJsonNull())
            requiredString(approval, "reviewed_at");
    }

    private static void validateRegistrationApproval(JsonObject root) throws IOException {
        var registrationSha256 = requiredSha256(root, "registration_sha256");
        if (!root.has("registration_approval")
                || !root.get("registration_approval").isJsonObject())
            throw new IOException("Schema-4 bundle has no registration approval");
        var approval = root.getAsJsonObject("registration_approval");
        requiredSha256(approval, "approval_sha256");
        if (!registrationSha256.equals(
                requiredSha256(approval, "registration_result_sha256")))
            throw new IOException("Schema-4 registration approval fingerprint is stale");
        requiredString(approval, "order_fingerprint");
        requiredString(approval, "reviewer");
        requiredString(approval, "reviewed_at");
    }

    private static String requiredSha256(JsonObject object, String name)
            throws IOException {
        var value = requiredString(object, name);
        if (!value.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Histopia bundle contains an invalid " + name);
        return value.toLowerCase();
    }

    private static String requiredString(JsonObject object, String name)
            throws IOException {
        var value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())
            throw new IOException("Histopia bundle contains an invalid " + name);
        return value.getAsString();
    }

    private static int requiredInteger(JsonObject object, String name)
            throws IOException {
        var value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber())
            throw new IOException("Histopia bundle contains an invalid " + name);
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new IOException("Histopia bundle contains an invalid " + name, error);
        }
    }

    private static int requiredNonnegativeInteger(JsonObject object, String name)
            throws IOException {
        var value = requiredInteger(object, name);
        if (value < 0)
            throw new IOException("Histopia bundle contains a negative " + name);
        return value;
    }

    private static long requiredNonnegativeLong(JsonObject object, String name)
            throws IOException {
        var value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber())
            throw new IOException("Histopia bundle contains an invalid " + name);
        final long parsed;
        try {
            parsed = value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException error) {
            throw new IOException("Histopia bundle contains an invalid " + name, error);
        }
        if (parsed < 0)
            throw new IOException("Histopia bundle contains a negative " + name);
        return parsed;
    }

    private static int optionalNonnegativeInteger(JsonObject object, String name)
            throws IOException {
        return object.has(name) ? requiredNonnegativeInteger(object, name) : -1;
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
