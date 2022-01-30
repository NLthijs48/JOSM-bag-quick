package me.wiefferink.bagquick;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import static org.openstreetmap.josm.tools.I18n.tr;

// TODO:
// - Address node support (when updating geometry: check that address nodes are still inside)
// - Update existing way support
// - Cascade to surrounding buildings support (and protection of surroundings)
// - Better feedback/history panel?

/**
 * Action to quickly perform BAG updates
 * - one-click updates
 * - one-click imports
 */
public class BagUpdateAction extends MapMode implements MouseListener {

    public BagUpdateAction() {
        // TODO update icon
        super(
            tr("Bag update"),
            "areaselector",
            tr("Update the geometry and tags of a building based on data from the BAG (in The Netherlands only)."),
            Shortcut.registerShortcut(
                "tools:bagquick",
                tr("Tools: {0}", tr("Bag Quick Update")),
                KeyEvent.VK_A,
                Shortcut.ALT_CTRL
            ),
            getCursor()
        );
    }

    private static Cursor getCursor() {
        // TODO: update overlay image
        return ImageProvider.getCursor("crosshair", "areaselector");
    }

    @Override
    public void enterMode() {
        if (!isEnabled()) {
            // Don't do anything if the action is disabled (TODO: figure out why enterMode() is called in that case)
            return;
        }
        super.enterMode();

        // Enable a special cursor image
        MainApplication.getMap().mapView.setCursor(getCursor());
        // Listen to mouse events
        MainApplication.getMap().mapView.addMouseListener(this);
    }

    @Override
    public void exitMode() {
        super.exitMode();

        // Stop listening to mouse events
        MainApplication.getMap().mapView.removeMouseListener(this);

        // TODO: cleanup cursor?
    }

    /**
     * Invoked when the mouse button has been clicked (pressed and released) on.
     */
    @Override
    public void mouseClicked(MouseEvent e) {

        Logging.info("mouse clicked " + e);

        requestFocusInMapView();
        updateKeyModifiers(e);
        if (e.getButton() != MouseEvent.BUTTON1) {
            // Not a leftclick, ignore
            return;
        }

        Point clickedPoint = e.getPoint();
        // Run an async task to do the work
        SwingUtilities.invokeLater(() -> {
            try {
                BuildingUpdate buildingUpdate = new BuildingUpdate(clickedPoint);
                buildingUpdate.execute();
            } catch (Exception ex) {
                Logging.error("Failed to do a BAG update");
                Logging.error(ex);

                // TODO: add custom bug support dialog like AreaSelector has
                //new BugReportDialog(ex);
            }
        });
    }

}
