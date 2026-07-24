package qupath.ext.histopia;

import javafx.scene.control.MenuItem;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath entry point for Histopia interoperability.
 */
public final class HistopiaExtension implements QuPathExtension {

    private boolean installed;
    private HistopiaPanel panel;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed)
            return;
        installed = true;
        var menu = qupath.getMenu("Extensions>Histopia", true);
        var open = new MenuItem("Open Histopia tools");
        open.setOnAction(event -> {
            if (panel == null)
                panel = new HistopiaPanel(qupath);
            panel.show();
        });
        menu.getItems().add(open);
    }

    @Override
    public String getName() {
        return "Histopia";
    }

    @Override
    public String getDescription() {
        return "Project-driven registration and semantic atlas workflows for Histopia";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("v0.7.0");
    }
}
