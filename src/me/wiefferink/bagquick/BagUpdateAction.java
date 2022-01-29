package me.wiefferink.bagquick;

import edu.princeton.cs.algs4.AssignmentProblem;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.RemoveNodesCommand;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Up to this number of nodes, do slow pair matching that is O(n^2)
     * - For higher node counts fall back to O(n*log(n)) algorithm
     * - TODO: probably up to 50 or so nodes is fine?
     */
    private static final int MAX_SLOW_PAIRING_NODE_COUNT = 20;
    private static final boolean DEBUG = true;

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
            createNewBuilding(bagWay);
            return;
        }

        Logging.info("    found OSM way: "+osmWay);
        updateExistingBuilding(bagWay, osmWay);
    }

    /**
     * Update an existing building with new geometry and tags
     */
    private void updateExistingBuilding(Way sourceWay, Way targetWay) {
        String bagRef = sourceWay.get("ref:bag");

        // Match source nodes to target nodes in a way that moves them as little as possible
        // - code roughly based on the ReplaceBuilding action
        Map<Node, Node> sourceToTargetNode = new HashMap<>();
        boolean useSlow = true;
        int sourceNodeCount = sourceWay.getNodesCount();
        List<Node> sourceNodes = sourceWay.getNodes();
        Set<Node> sourceNodesLeft = new HashSet<>(sourceNodes);
        int targetNodeCount = targetWay.getNodesCount();
        List<Node> targetNodes = targetWay.getNodes();
        Set<Node> targetNodesLeft = new HashSet<>(targetNodes);
        if (sourceNodeCount < MAX_SLOW_PAIRING_NODE_COUNT && targetNodeCount < MAX_SLOW_PAIRING_NODE_COUNT) {  // use robust, but slower assignment
            int N = Math.max(sourceNodeCount, targetNodeCount);
            double[][] cost = new double[N][N];
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    cost[i][j] = Double.MAX_VALUE;
                }
            }

            // TODO: should there be a max distance? Maybe only for nodes that are attached to other things
            double maxDistance = 1;
            for (int sourceNodeIndex = 0; sourceNodeIndex < sourceNodeCount; sourceNodeIndex++) {
                for (int targetNodeIndex = 0; targetNodeIndex < targetNodeCount; targetNodeIndex++) {
                    double distance = sourceNodes.get(sourceNodeIndex).getCoor().distance(targetNodes.get(targetNodeIndex).getCoor());
                    if (distance > maxDistance) {
                        cost[sourceNodeIndex][targetNodeIndex] = Double.MAX_VALUE;
                    } else {
                        cost[sourceNodeIndex][targetNodeIndex] = distance;
                    }
                }
            }
            AssignmentProblem assignment;
            try {
                assignment = new AssignmentProblem(cost);
                for (int sourceNodeIndex = 0; sourceNodeIndex < N; sourceNodeIndex++) {
                    int targetNodeIndex = assignment.sol(sourceNodeIndex);
                    if (cost[sourceNodeIndex][targetNodeIndex] != Double.MAX_VALUE) {
                        Node targetNode = targetNodes.get(targetNodeIndex);
                        Node sourceNode = sourceNodes.get(sourceNodeIndex);

                        if (!targetNodesLeft.contains(targetNode)) {
                            Logging.info("targetNode already mapped to another sourceNode: ", targetNode, sourceNode);
                            continue;
                        }
                        if (!sourceNodesLeft.contains(sourceNode)) {
                            Logging.info("sourceNode already mapped to another targetNode: ", sourceNode, targetNode);
                            continue;
                        }

                        targetNodesLeft.remove(targetNode);
                        sourceNodesLeft.remove(sourceNode);
                        sourceToTargetNode.put(sourceNode, targetNode);
                    }
                }
            } catch (Exception e) {
                useSlow = false;
                notification(
                    tr("Exceeded iteration limit for robust method, using simpler method."),
                    JOptionPane.WARNING_MESSAGE
                );
                sourceToTargetNode = new HashMap<>();
            }
        }

        // TODO: implement a backup, like ReplaceBuilding does
        if (!useSlow) {
            /*
            for (Node n : geometryPool) {
                Node nearest = findNearestNode(n, nodePool);
                if (nearest != null) {
                    nodePairs.put(n, nearest);
                    nodePool.remove(nearest);
                }
            }
            */
        }

        if (DEBUG) {
            Logging.info("Resulting node pairs:");
            for (Map.Entry<Node, Node> entry : sourceToTargetNode.entrySet()) {
                Logging.info("    Pair:");
                Node sourceNode = entry.getValue();
                Logging.info("        source: {0} {1}", sourceNode.get("name"), sourceNode.getCoor());
                Node targetNode = entry.getKey();
                Logging.info("        target: {0} {1}", targetNode.get("name"), targetNode.getCoor());
                Logging.info("        distance: {0}", sourceNode.getCoor().distance(targetNode.getCoor()));
            }

            Logging.info("Leftover source nodes:");
            for (Node sourceNodeLeft : sourceNodesLeft) {
                Logging.info("    {0} {1}", sourceNodeLeft.get("name"), sourceNodeLeft.getCoor());
            }

            Logging.info("Leftover target nodes:");
            for (Node targetNodeLeft : targetNodesLeft) {
                Logging.info("    {0} {1}", targetNodeLeft.get("name"), targetNodeLeft.getCoor());
            }
        }

        // Apply node updates
        // - Loop through the source nodes in-order here to build the Way correctly
        List<Node> resultNodes = new LinkedList<>();
        List<Node> nodesToAdd = new LinkedList<>();
        for (Node sourceNode : sourceNodes) {
            Node resultNode;
            LatLon sourceCoor = sourceNode.getCoor();
            if (sourceToTargetNode.containsKey(sourceNode)) {
                // Use the mapped target node (update location later with MoveCommand)
                resultNode = sourceToTargetNode.get(sourceNode);
            } else {
                // Create a new Node, an additional one is required
                resultNode = new Node();
                resultNode.setCoor(sourceCoor);
                nodesToAdd.add(resultNode);
            }

            resultNodes.add(resultNode);
        }

        // Create new nodes
        Collection<Command> createNodesCommands = new LinkedList<>();
        if (!nodesToAdd.isEmpty()) {
            for (Node nodeToAdd : nodesToAdd) {
                createNodesCommands.add(new AddCommand(targetWay.getDataSet(), nodeToAdd));
            }

            // TODO: ideally do this in the same Sequence as below, test if that works
            UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded(tr("Create nodes"), createNodesCommands));
        }

        // Collect commands
        Collection<Command> updateBuildingCommands = new LinkedList<>();

        // Update nodes in the target Way
        // TODO: skip when the same
        updateBuildingCommands.add(new ChangeNodesCommand(targetWay, resultNodes));

        // Move existing nodes to the correct location
        // TODO: double check if nodes are allowed to be moved:
        // - warn when not uninteresting (see replace building plugin)
        // - warn when part of other ways (like a connected house/fence/etc)
        int nodesUpToDate = 0;
        int nodesMoved = 0;
        for (Node sourceNode : sourceToTargetNode.keySet()) {
            Node targetNode = sourceToTargetNode.get(sourceNode);
            LatLon sourceCoor = sourceNode.getCoor();
            if (sourceCoor.equalsEpsilon(targetNode.getCoor())) {
                nodesUpToDate++;
                continue;
            }
            nodesMoved++;
            updateBuildingCommands.add(new MoveCommand(targetNode, sourceCoor));
        }

        // Remove nodes that are not used anymore
        // TODO: double check if nodes can be removed:
        // - not allowed when tagged with something (not uninteresting)
        // - not allowed when part of other ways (building:part, other house, fence, etc)
        if (!targetNodesLeft.isEmpty()) {
            updateBuildingCommands.add(new RemoveNodesCommand(targetWay, targetNodesLeft));
        }

        // Update start_date tag
        Command updateStartDateCommand = applyStartDate(sourceWay, targetWay);
        if (updateStartDateCommand != null) {
            updateBuildingCommands.add(updateStartDateCommand);
            // TODO: add this to the notification as well
        }

        // Detect no updates case
        if (updateBuildingCommands.isEmpty()) {
            Logging.info("Building is already up-to-date");
            notification(tr("Building is already up-to-date"));
            return;
        }

        // Execute the changes
        // TODO: better history summary message (can also be used in the notification)
        Command combinedCommand = SequenceCommand.wrapIfNeeded(tr("BAG update of {0}", bagRef), updateBuildingCommands);
        UndoRedoHandler.getInstance().add(combinedCommand);

        // Notify the user about the results
        String summaryText = tr("Updated BAG building {0}:<br />{1} already up-to-date<br />{2} nodes moved<br />{3} nodes created<br />{4} nodes removed", bagRef, nodesUpToDate, nodesMoved, sourceNodesLeft.size(), targetNodesLeft.size());
        Logging.info(summaryText);
        notification(summaryText);
    }

    /**
     * Create the given Way in the OSM layer
     */
    private void createNewBuilding(Way sourceWay) {
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
            .setDuration(Notification.TIME_LONG)
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
