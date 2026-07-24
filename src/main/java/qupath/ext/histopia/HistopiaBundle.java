package qupath.ext.histopia;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class HistopiaBundle {

    private HistopiaBundle() {
    }

    static Path findSemanticAnnotations(Path manifest, Set<String> imageNames)
            throws IOException {
        try (var reader = Files.newBufferedReader(manifest)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"histopia-qupath-bundle".equals(root.get("format").getAsString()))
                throw new IOException("Selected JSON is not a Histopia QuPath bundle");
            for (var element : root.getAsJsonArray("slides")) {
                var slide = element.getAsJsonObject();
                var id = slide.get("id").getAsString();
                if (!imageNames.contains(id) || !slide.has("semantic_annotations"))
                    continue;
                var relative = slide.get("semantic_annotations").getAsString();
                var path = manifest.getParent().resolve(relative).normalize();
                if (!path.startsWith(manifest.getParent().normalize()))
                    throw new IOException("Bundle annotation path escapes its directory");
                if (!Files.isRegularFile(path))
                    throw new IOException("Bundle annotation file is missing: " + relative);
                return path;
            }
        } catch (IllegalStateException | NullPointerException error) {
            throw new IOException("Histopia bundle manifest is malformed", error);
        }
        throw new IOException(
                "The open image does not match a semantic slide in this bundle");
    }
}
