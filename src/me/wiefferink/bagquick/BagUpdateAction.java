package me.wiefferink.bagquick;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.openstreetmap.josm.tools.I18n.tr;

// TODO:
// - Address node support
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

        // TODO: instead of this, check that we have the correct BAG layers, and send updates to the correct one
        /*
        if (!MainApplication.getMap().mapView.isActiveLayerDrawable()) {
            return;
        }
         */

        requestFocusInMapView();
        updateKeyModifiers(e);
        if (e.getButton() != MouseEvent.BUTTON1) {
            // Not a leftclick, ignore
            return;
        }

        Point clickedPoint = e.getPoint();
        LatLon coordinates = MainApplication.getMap().mapView.getLatLon(clickedPoint.x, clickedPoint.y);

        // Run an async task to do the work
        SwingUtilities.invokeLater(() -> {
            try {
                doBagUpdate(clickedPoint);
            } catch (Exception ex) {
                Logging.error("Failed to do a BAG update");
                Logging.error(ex);

                // TODO: add custom bug support dialog like AreaSelector has
                //new BugReportDialog(ex);
            }
        });
    }

    /**
     * Start a bag update on a certain clicked point
     * - detects the Way's that have been clicked
     * - performs updates on the OSM layer
     */
    public void doBagUpdate(Point clickedPoint) {
        // TODO:
        // Place the BAG building on the target layer
        // - correct building tag
        // - correct start_date tag
        // - skip those temporary tags that come from the WFS layer
        // Existing building?
        // - no ref:bag? prompt
        // - has ref:bag, but does not match? prompt
        // - buildings attached to the old one? deal with that (auto-update connected building?)
        // - do a 'replace geometry' with the new and existing building
        // Do a 'mergeNodes()' for any nodes of buildings next to the new one
        // Find and perform 'N' on nodes of the new building that are on top of ways of other buildings, and the other way around
        // Show some kind of summary of the changes in a panel or toast

        MapView mapView = MainApplication.getMap().mapView;

        // Sanity check layers
        if (getBagDataSet() == null) {
            notification(tr("BAG ODS layer not found! Make sure to use ODS > Enable > BAG first"));
            return;
        }
        if (getOsmDataSet() == null) {
            notification(tr("BAG OSM layer not found! Make sure to use ODS > Enable > BAG first"));
            return;
        }

        // Search box around the clicked point
        //int snapDistance = Config.getPref().getInt("mappaint.segment.snap-distance", 10);
        int snapDistance = 100;
        BBox searchBbox = new BBox(
            mapView.getLatLon(clickedPoint.x - snapDistance, clickedPoint.y - snapDistance),
            mapView.getLatLon(clickedPoint.x + snapDistance, clickedPoint.y + snapDistance)
        );
        LatLon clickedLatLon = mapView.getLatLon(clickedPoint.x, clickedPoint.y);
        Logging.info("clicked LatLon="+clickedLatLon);

        // Find the Way on the BAG ODS layer
        Way bagWay = findBagWay(clickedLatLon, searchBbox);
        if (bagWay == null) {
            return;
        }
        String bagRef = bagWay.get("ref:bag");
        Logging.info("    ref:bag="+ bagRef);
        if (bagRef == null || bagRef.isEmpty()) {
            notification(tr("Clicked Way in the BAG ODS layer has no ref:bag! Try another building"));
            return;
        }

        // Find the Way on the OSM layer
        Way osmWay = findOsmWay(clickedLatLon, searchBbox, bagRef);
        if (osmWay == null) {
            createNewTarget(bagWay);
            return;
        }

        Logging.info("    found OSM way: "+osmWay);
        notification("Found BAG ref "+bagRef+", with matching existing way "+osmWay.getId()+" 🎊 😁");

        // Track changes in a list
        Collection<Command> commands = new LinkedList<>();

        // TODO: do building outline changes


        notification(tr("TODO: Updated BAG building: {0}", bagRef));

        /*
        // Update start_date tag
        Command updateStartDateCommand = applyStartDate(bagWay, osmWay);
        // TODO: collect human-readable changes somewhere
        if (updateStartDateCommand != null) {
            commands.add(updateStartDateCommand);
        }

        // TODO: better history summary message (can also be used in the notification)
        Command combinedCommand = SequenceCommand.wrapIfNeeded(tr("BAG update of way {0}", osmWay.getId()), commands);
        UndoRedoHandler.getInstance().add(combinedCommand);
        */
    }

    /**
     * Create the given Way in the OSM layer
     */
    private void createNewTarget(Way sourceWay) {
        Logging.info("Creating a new BAG way");
        String bagRef = sourceWay.get("ref:bag");
        Collection<Command> wayAndNodesCommands = new LinkedList<>();
        DataSet dataSet = getOsmDataSet(); // TODO: check if this layer needs to be active, ideally not

        // Create a way based on the source coordinates
        Way targetWay = new Way();
        for (Node sourceNode : sourceWay.getNodes()) {
            Node targetNode = new Node();
            targetNode.setCoor(sourceNode.getCoor());
            targetWay.addNode(targetNode);
            wayAndNodesCommands.add(new AddCommand(dataSet, targetNode));
        }
        wayAndNodesCommands.add(new AddCommand(dataSet, targetWay));

        // Execute adding way+nodes
        Command wayAndNodesCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: way+nodes: {0}", bagRef), wayAndNodesCommands);
        UndoRedoHandler.getInstance().add(wayAndNodesCommand);

        // Apply all tags of the source to the target (at least building/ref:bag/source/source:date/start_date)
        Collection<Command> tagsCommands = new LinkedList<>();
        for (Map.Entry<String, String> sourceTag : sourceWay.getKeys().entrySet()) {
            // Ignore tags prefixed with |ODS, those are only meant as background information
            if (sourceTag.getKey().startsWith("|ODS")) {
                continue;
            }

            Logging.info("    adding tag {0}={1}", sourceTag.getKey(), sourceTag.getValue());
            tagsCommands.add(new ChangePropertyCommand(targetWay, sourceTag.getKey(), sourceTag.getValue()));
        }

        // TODO: when building=contruction warn about importing it? Or just use building=yes? Check with forum

        // Execute the changes
        Command tagsCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: add tags: {0}", bagRef), tagsCommands);
        UndoRedoHandler.getInstance().add(tagsCommand);

        // TODO: import address nodes as well

        // Notify about the result
        String message = "New BAG building imported:<br />";
        for (String targetKey : targetWay.getKeys().keySet()) {
            message += "<br />" + targetKey + "=" + targetWay.get(targetKey);
        }
        notification(tr(message));
    }

    private Command applyStartDate(Way source, Way target) {
        // Check if the tag is present
        if (!source.hasTag("start_date")) {
            return null;
        }

        // Check if the value makes sense
        String sourceStartData = source.get("start_date");
        if (sourceStartData == null || sourceStartData.isEmpty()) {
            return null;
        }

        // Check the target value
        String targetStartDate = target.get("start_date");
        if (sourceStartData.equals(targetStartDate)) {
            return null;
        }

        // Apply the tag change
        return new ChangePropertyCommand(target, "start_date", sourceStartData);

    }

    private static void notification(String message) {
        notification(message, JOptionPane.INFORMATION_MESSAGE);
    }
    private static void notification(String message, int messageType) {
        Logging.info("notification: "+message);
        new Notification("<strong>" + tr("Bag Quick") + "</strong><br />" + message)
            .setIcon(messageType)
            .show();
    }

    /**
     * Find the BAG way that matches the clicked location
     * @return Way when a good match has been found, otherwise null
     */
    private Way findBagWay(LatLon clickedLatLon, BBox searchBbox) {
        // Find the BAG ODS wfs layer, with the data from the BAG
        DataSet bagDataSet = getBagDataSet();

        // Get ways around the clicked point
        List<Way> bagSearchWays = bagDataSet.searchWays(searchBbox);
        for (Way way : bagSearchWays) {
            Logging.info("    way:"+way.getId());
            Logging.info("        area="+way.isArea());
            Logging.info("        containsNode="+way.getBBox().bounds(clickedLatLon));
            Logging.info("        building="+way.get("building"));
            Logging.info("        ref:bag="+way.get("ref:bag"));
        }
        List<Way> bagMatchingWays = bagSearchWays
                .stream()
                // Way should be an area (not an address point or some other line)
                .filter(Way::isArea)
                // Way should surround the clicked point
                // TODO: inaccurate for complex polygons, is there a fix for that?
                .filter(way -> way.getBBox().bounds(clickedLatLon))
                // Has a building=* tag
                .filter(way -> way.hasTag("building"))
                .collect(Collectors.toList());
        if (bagMatchingWays.isEmpty()) {
            return null;
        }
        if (bagMatchingWays.size() > 1) {
            // TODO: let the user select one from a list?
            //notification(tr("Found multiple BAG ways..."));
            Logging.info("Found multiple BAG ways: ", bagMatchingWays.stream().map(way -> way.getDisplayName(DefaultNameFormatter.getInstance())).collect(Collectors.joining(", ")));
            return null;
        }
        Way bagWay = bagMatchingWays.get(0);
        Logging.info("    found ODS way: "+bagWay);
        return bagWay;
    }

    /**
     * Find an OSM way that matches a certain ref:bag value
     * @return Way when a good match has been found, otherwise null
     */
    private Way findOsmWay(LatLon clickedLatLon, BBox searchBbox, String bagRef) {
        // Find the OSM layer
        DataSet osmDataSet = getOsmDataSet();

        // Get ways around the clicked point
        // TODO: search by ref:bag first
        List<Way> osmSearchWays = osmDataSet.searchWays(searchBbox);
        for (Way way : osmSearchWays) {
            Logging.info("    way:"+way.getId());
            Logging.info("        area="+way.isArea());
            Logging.info("        containsNode="+way.getBBox().bounds(clickedLatLon));
            Logging.info("        building="+way.get("building"));
            Logging.info("        ref:bag="+way.get("ref:bag"));
        }
        List<Way> osmMatchingWays = osmSearchWays
                .stream()
                // Way should be an area (not an address point or some other line)
                .filter(Way::isArea)
                // Way should surround the clicked point
                // TODO: inaccurate for complex polygons, is there a fix for that?
                .filter(way -> way.getBBox().bounds(clickedLatLon))
                // BAG ref matches
                .filter(way -> bagRef == null || bagRef.equals(way.get("ref:bag")))
                .collect(Collectors.toList());
        if (osmMatchingWays.isEmpty()) {
            //notification(tr("Did not find a house in the BAG OSM layer, try again"));
            return null;
        }
        if (osmMatchingWays.size() > 1) {
            // TODO: this is weird, ref:bag should never have duplicates
            // TODO: prevent creating new Way because of returning null here
            Logging.info("Found multiple BAG ways: ", osmMatchingWays.stream().map(way -> way.getDisplayName(DefaultNameFormatter.getInstance())).collect(Collectors.joining(", ")));
            return null;
        }
        return osmMatchingWays.get(0);
    }

    private DataSet getBagDataSet() {
        return getLayerDataSetByName("BAG ODS");
    }

    private DataSet getOsmDataSet() {
        return getLayerDataSetByName("BAG OSM");
    }

    /**
     * Get the DataSet from a map Layer by name
     * @return DataSet when there is a matching Layer with a DataSet, null when not found
     */
    private DataSet getLayerDataSetByName(String name) {
        MapView mapView = MainApplication.getMap().mapView;
        Layer result = null;
        for (Layer layer : mapView.getLayerManager().getLayers()) {
            if (layer.getName().equals(name)) {
                result = layer;
            }
        }

        if (result == null) {
            return null;
        }

        // Skip non-data layers
        if (!(result instanceof OsmDataLayer)) {
            return null;
        }

        // Get the data from the layer
        return ((OsmDataLayer) result).getDataSet();
    }

}
