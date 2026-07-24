package qupath.ext.histopia;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.io.PathIO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

final class HistopiaPanel {

    private static final Logger logger = LoggerFactory.getLogger(HistopiaPanel.class);
    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final TextField python = new TextField("python");
    private final TextField registration = new TextField();
    private final TextField semantic = new TextField();
    private final TextField output = new TextField();
    private final CheckBox includeSemantic = new CheckBox("Include semantic atlas");
    private final Spinner<Integer> clusters = new Spinner<>(2, 50, 7);
    private final Button export = new Button("Export bundle");
    private final Label status = new Label("Ready");

    HistopiaPanel(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.setTitle("Histopia");
        stage.setScene(new Scene(createContent()));
        stage.setMinWidth(620);
        includeSemantic.selectedProperty().addListener(
                (observable, oldValue, selected) -> {
                    semantic.setDisable(!selected);
                    clusters.setDisable(!selected);
                });
        semantic.setDisable(true);
        clusters.setDisable(true);
    }

    void show() {
        stage.show();
        stage.toFront();
    }

    private GridPane createContent() {
        var grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(8);
        grid.setVgap(10);
        addDirectoryRow(grid, 0, "Registration run", registration);
        addDirectoryRow(grid, 1, "Semantic run", semantic);
        addDirectoryRow(grid, 2, "Output bundle", output);
        grid.add(new Label("Python executable"), 0, 3);
        grid.add(python, 1, 3);
        grid.add(includeSemantic, 1, 4);
        grid.add(new Label("Semantic K"), 0, 5);
        grid.add(clusters, 1, 5);

        var importButton = new Button("Import current slide");
        export.setOnAction(event -> exportBundle());
        importButton.setOnAction(event -> importGeoJson());
        grid.add(new HBox(8, export, importButton), 1, 6);
        grid.add(status, 1, 7);
        return grid;
    }

    private void addDirectoryRow(
            GridPane grid, int row, String label, TextField field) {
        var browse = new Button("Browse");
        browse.setOnAction(event -> {
            var chooser = new DirectoryChooser();
            chooser.setTitle(label);
            var selected = chooser.showDialog(stage);
            if (selected != null)
                field.setText(selected.toPath().toString());
        });
        field.setPrefColumnCount(36);
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
        grid.add(browse, 2, row);
    }

    private void exportBundle() {
        try {
            var semanticPath = includeSemantic.isSelected()
                    ? requiredPath(semantic, "Semantic run")
                    : null;
            var command = HistopiaCommand.exportBundle(
                    python.getText(),
                    requiredPath(registration, "Registration run"),
                    requiredPath(output, "Output bundle"),
                    semanticPath,
                    semanticPath == null ? null : clusters.getValue());
            export.setDisable(true);
            status.setText("Exporting...");
            CompletableFuture
                    .supplyAsync(() -> run(command))
                    .whenComplete((message, error) -> Platform.runLater(() -> {
                        export.setDisable(false);
                        if (error == null) {
                            status.setText("Bundle exported");
                            Dialogs.showInfoNotification("Histopia", message);
                        } else {
                            status.setText("Export failed");
                            Dialogs.showErrorMessage("Histopia export", error.getMessage());
                        }
                    }));
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia export", error.getMessage());
        }
    }

    private String run(java.util.List<String> command) {
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String outputText;
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                outputText = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            var exitCode = process.waitFor();
            if (exitCode != 0)
                throw new IllegalStateException(
                        "Histopia exited with code " + exitCode + System.lineSeparator() + outputText);
            return outputText.isBlank() ? "Bundle export completed" : outputText;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not start Histopia. Check the Python executable and installation.",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Histopia export was interrupted", error);
        }
    }

    private void importGeoJson() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("Histopia import", "Open the matching source slide first.");
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("Select histopia-qupath.json");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Histopia bundle", "*.json"));
        var selected = chooser.showOpenDialog(stage);
        if (selected == null)
            return;
        try {
            var names = new HashSet<String>();
            names.add(imageData.getServer().getMetadata().getName());
            for (var uri : imageData.getServer().getURIs()) {
                var path = uri.getPath();
                if (path != null)
                    names.add(Path.of(path).getFileName().toString());
            }
            var annotationPath = HistopiaBundle.findSemanticAnnotations(
                    selected.toPath(), names);
            var objects = PathIO.readObjects(annotationPath.toFile());
            imageData.getHierarchy().addObjects(objects);
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
            Dialogs.showInfoNotification(
                    "Histopia",
                    "Imported " + objects.size() + " semantic region objects.");
        } catch (IOException error) {
            logger.error("Could not import Histopia GeoJSON", error);
            Dialogs.showErrorMessage("Histopia import", error.getMessage());
        }
    }

    private static Path requiredPath(TextField field, String name) {
        if (field.getText() == null || field.getText().isBlank())
            throw new IllegalArgumentException(name + " is required");
        return Path.of(field.getText());
    }
}
