package me.wiefferink.bagquick;

import edu.princeton.cs.algs4.AssignmentProblem;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
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
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;

import java.awt.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

// TODO:
// - extract findOsmWay and findBagWay to a Search class
// - extract node pairing algorithms to separate classes implementing a certain interface
// - collect info/warning notes for a summary at the end

/**
 * Represents a 1-click BAG to OSM building update run
 * - structures the different steps of the update
 * - collects results of the update
 */
public class BuildingUpdate {

	/**
	 * Up to this number of nodes, do slow pair matching that is O(n^2)
	 * - For higher node counts fall back to O(n*log(n)) algorithm
	 * - TODO: probably up to 50 or so nodes is fine?
	 */
	private static final int MAX_SLOW_PAIRING_NODE_COUNT = 20;

	/** The point on the map that has been clicked with the update tool */
	private final Point clickedPoint;
	/** The LatLon on the map that has been clicked with the update tool */
	private final LatLon clickedLatLon;

	/** MapView */
	private final MapView mapView;

	// Layers between which the update is done
	/** DataSet of the BAG ODS layer */
	private DataSet bagDataSet;
	/** DataSet of the BAG OSM layer */
	private DataSet osmDataSet;

	// Ways used for the update
	/** BAG way that needs to be updated in OSM */
	private Way bagWay;
	/** Existing way in OSM that needs to be updated, or null when there is none */
	private Way osmWay;

	// Summary
	private ResultSummary resultSummary;

	public BuildingUpdate(Point clickedPoint) {
		this.resultSummary = new ResultSummary();
		this.mapView = MainApplication.getMap().mapView;
		this.clickedPoint = clickedPoint;
		this.clickedLatLon = this.mapView.getLatLon(clickedPoint.x, clickedPoint.y);
	}

	/** Starting point for the update */
	public boolean execute() {
		boolean result = executeInternal();
		resultSummary.sendNotification();
		return result;
	}

	public boolean executeInternal() {
		BagQuickPlugin.debug("BuildingUpdate.execute()");
		BagQuickPlugin.debug("clicked LatLon={0}", clickedLatLon);

		// Check that the BAG ODS and BAG OSM layers are present
		if (!checkLayers()) {
			return false;
		}

		// Find the clicked way on the BAG ODS layer
		if (!findBagWay()) {
			return false;
		}

		// Find the Way on the OSM layer
		if (!findOsmWay()) {
			return createNewBuilding();
		}

		BagQuickPlugin.debug("    found OSM way: "+osmWay);
		return updateExistingBuilding();
	}

	/** Gather the BAG and OSM data sets */
	private boolean checkLayers() {
		this.bagDataSet = getLayerDataSetByName("BAG ODS");
		if (bagDataSet == null) {
			resultSummary.failed(tr("BAG ODS layer not found! Make sure to use ODS > Enable > BAG first"));
			return false;
		}

		this.osmDataSet = getLayerDataSetByName("BAG OSM");
		if (osmDataSet == null) {
			resultSummary.failed(tr("BAG OSM layer not found! Make sure to use ODS > Enable > BAG first"));
			return false;
		}

		return true;
	}

	/**
	 * Get the DataSet from a map Layer by name
	 * @return DataSet when there is a matching Layer with a DataSet, null when not found
	 */
	private DataSet getLayerDataSetByName(String name) {
		Layer result = null;
		for (Layer layer : this.mapView.getLayerManager().getLayers()) {
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

	/**
	 * Find the BAG way that matches the clicked location
	 * - When not found it directly notifies the user
	 */
	private boolean findBagWay() {
		// Get ways around the clicked point
		java.util.List<Way> bagSearchWays = this.bagDataSet.searchWays(getSearchBox());
		BagQuickPlugin.debug("findBagWay() {0} results:", bagSearchWays.size());
		printWayList(bagSearchWays);

		Node clickedNode = new Node(this.clickedLatLon);
		java.util.List<Way> bagMatchingWays = bagSearchWays
				.stream()
				// Way should be an area (not an address point or some other line)
				.filter(Way::isArea)
				// Has a building=* tag
				.filter(way -> way.hasTag("building"))
				// Clicked point should be inside the way (quick filter to get rid of most)
				.filter(way -> way.getBBox().bounds(this.clickedLatLon))
				// Clicked point should be inside the way (strict filter, dealing with polygons)
				.filter(way -> Geometry.nodeInsidePolygon(clickedNode, way.getNodes()))
				// To a list
				.collect(Collectors.toList());

		// No result
		if (bagMatchingWays.isEmpty()) {
			resultSummary.failed(tr("Did not find a BAG building, try another location"));
			return false;
		}

		// Multiple results
		if (bagMatchingWays.size() > 1) {
			// This should essentially never happen, but could offer a selection UI in the future here
			String bagWayList = bagMatchingWays.stream().map(way -> way.getDisplayName(DefaultNameFormatter.getInstance())).collect(Collectors.joining("<br />"));
			resultSummary.failed("Found multiple BAG ways, don't know which to update: <br />"+bagWayList);
			return false;
		}

		// 1 result
		Way result =  bagMatchingWays.get(0);

		// Check ref:bag presence
		String bagRef = result.get("ref:bag");
		if (bagRef == null || bagRef.isEmpty()) {
			resultSummary.failed(tr("Clicked way in the BAG ODS layer has no ref:bag! Try another building"));
			return false;
		}

		BagQuickPlugin.debug("findBagWay() ref:bag="+ bagRef);
		BagQuickPlugin.debug("    found BAG way: "+bagWay);
		this.bagWay = result;
		return true;
	}

	/**
	 * Find an OSM way that matches a certain ref:bag value
	 * - Note: this does not send notifications, just indicates if found
	 * @return Way when a good match has been found, otherwise null
	 */
	private boolean findOsmWay() {
		String bagRef = bagWay.get("ref:bag");

		// Get ways around the clicked point
		java.util.List<Way> osmSearchWays = this.osmDataSet.searchWays(getSearchBox());
		BagQuickPlugin.debug("findOsmWay() {0} search results:", osmSearchWays.size());
		printWayList(osmSearchWays);

		List<Way> osmMatchingWays = osmSearchWays
				.stream()
				// Way should be an area (not an address point or some other line)
				.filter(Way::isArea)
				// Way should surround the clicked point
				.filter(way -> way.getBBox().bounds(this.clickedLatLon))
				// BAG ref matches
				.filter(way -> bagRef != null && bagRef.equals(way.get("ref:bag")))
				.collect(Collectors.toList());

		// No result
		if (osmMatchingWays.isEmpty()) {
			return false;
		}

		// Multiple results
		if (osmMatchingWays.size() > 1) {
			// TODO: this is weird, ref:bag should never have duplicates
			// TODO: prevent creating new Way because of returning null here
			resultSummary.failed(tr("Found multiple BAG ways: {0}", osmMatchingWays.stream().map(way -> way.getDisplayName(DefaultNameFormatter.getInstance())).collect(Collectors.joining(", "))));
			return false;
		}

		// 1 result
		this.osmWay = osmMatchingWays.get(0);
		return true;
	}

	/**
	 * Update an existing building with new geometry and tags
	 */
	private boolean updateExistingBuilding() {
		String bagRef = bagWay.get("ref:bag");

		// Match BAG nodes to OSM nodes in a way that moves them as little as possible
		// - code roughly based on the ReplaceBuilding action
		Map<Node, Node> bagToOsmNode = new HashMap<>();
		boolean useSlow = true;
		int bagNodeCount = bagWay.getNodesCount();
		List<Node> bagNodes = bagWay.getNodes();
		Set<Node> bagNodesLeft = new HashSet<>(bagNodes);
		int osmNodeCount = osmWay.getNodesCount();
		List<Node> osmNodes = osmWay.getNodes();
		Set<Node> osmNodesLeft = new HashSet<>(osmNodes);
		if (bagNodeCount < MAX_SLOW_PAIRING_NODE_COUNT && osmNodeCount < MAX_SLOW_PAIRING_NODE_COUNT) {  // use robust, but slower assignment
			int N = Math.max(bagNodeCount, osmNodeCount);
			double[][] cost = new double[N][N];
			for (int i = 0; i < N; i++) {
				for (int j = 0; j < N; j++) {
					cost[i][j] = Double.MAX_VALUE;
				}
			}

			// TODO: should there be a max distance? Maybe only for nodes that are attached to other things
			double maxDistance = 1;
			for (int bagNodeIndex = 0; bagNodeIndex < bagNodeCount; bagNodeIndex++) {
				for (int osmNodeIndex = 0; osmNodeIndex < osmNodeCount; osmNodeIndex++) {
					double distance = bagNodes.get(bagNodeIndex).getCoor().distance(osmNodes.get(osmNodeIndex).getCoor());
					if (distance > maxDistance) {
						cost[bagNodeIndex][osmNodeIndex] = Double.MAX_VALUE;
					} else {
						cost[bagNodeIndex][osmNodeIndex] = distance;
					}
				}
			}
			AssignmentProblem assignment;
			try {
				assignment = new AssignmentProblem(cost);
				for (int bagNodeIndex = 0; bagNodeIndex < N; bagNodeIndex++) {
					int osmNodeIndex = assignment.sol(bagNodeIndex);
					if (cost[bagNodeIndex][osmNodeIndex] != Double.MAX_VALUE) {
						Node osmNode = osmNodes.get(osmNodeIndex);
						Node bagNode = bagNodes.get(bagNodeIndex);

						if (!osmNodesLeft.contains(osmNode)) {
							continue;
						}
						if (!bagNodesLeft.contains(bagNode)) {
							continue;
						}

						osmNodesLeft.remove(osmNode);
						bagNodesLeft.remove(bagNode);
						bagToOsmNode.put(bagNode, osmNode);
					}
				}
			} catch (Exception e) {
				useSlow = false;
				resultSummary.addNote(tr("Exceeded iteration limit for robust method, using simpler method."));
				bagToOsmNode = new HashMap<>();
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

		// debug logging
		printNodePairs(bagToOsmNode);
		BagQuickPlugin.debug("Leftover BAG nodes:");
		for (Node bagNodeLeft : bagNodesLeft) {
			BagQuickPlugin.debug("    {0} {1}", bagNodeLeft.get("name"), bagNodeLeft.getCoor());
		}
		BagQuickPlugin.debug("Leftover OSM nodes:");
		for (Node osmNodeLeft : osmNodesLeft) {
			BagQuickPlugin.debug("    {0} {1}", osmNodeLeft.get("name"), osmNodeLeft.getCoor());
		}

		// Apply node updates
		// - Loop through the BAG nodes in-order here to build the Way correctly
		List<Node> resultNodes = new LinkedList<>();
		List<Node> nodesToAdd = new LinkedList<>();
		for (Node bagNode : bagNodes) {
			Node resultNode;
			LatLon bagCoor = bagNode.getCoor();
			if (bagToOsmNode.containsKey(bagNode)) {
				// Use the mapped target node (update location later with MoveCommand)
				resultNode = bagToOsmNode.get(bagNode);
			} else {
				// Create a new Node, an additional one is required
				resultNode = new Node();
				resultNode.setCoor(bagCoor);
				nodesToAdd.add(resultNode);
			}

			resultNodes.add(resultNode);
		}

		// Create new nodes
		Collection<Command> createNodesCommands = new LinkedList<>();
		if (!nodesToAdd.isEmpty()) {
			for (Node nodeToAdd : nodesToAdd) {
				createNodesCommands.add(new AddCommand(osmWay.getDataSet(), nodeToAdd));
			}

			// TODO: ideally do this in the same Sequence as below, test if that works
			UndoRedoHandler.getInstance().add(SequenceCommand.wrapIfNeeded(tr("Create nodes"), createNodesCommands));
		}

		// Collect commands
		Collection<Command> updateBuildingCommands = new LinkedList<>();

		// Update nodes in the OSM Way
		// TODO: skip when the same
		updateBuildingCommands.add(new ChangeNodesCommand(osmWay, resultNodes));

		// Move existing nodes to the correct location
		// TODO: double check if nodes are allowed to be moved:
		// - warn when not uninteresting (see replace building plugin)
		// - warn when part of other ways (like a connected house/fence/etc)
		int nodesUpToDate = 0;
		int nodesMoved = 0;
		for (Node bagNode : bagToOsmNode.keySet()) {
			Node osmNode = bagToOsmNode.get(bagNode);
			LatLon bagCoor = bagNode.getCoor();
			if (bagCoor.equalsEpsilon(osmNode.getCoor())) {
				nodesUpToDate++;
				continue;
			}
			nodesMoved++;
			updateBuildingCommands.add(new MoveCommand(osmNode, bagCoor));
		}
		if (nodesUpToDate > 0) {
			resultSummary.addNote(trn("{0} node up-to-date", "{0} nodes up-to-date", nodesUpToDate, nodesUpToDate));
		}
		if (nodesMoved > 0) {
			resultSummary.addNote(trn("{0} node moved", "{0} nodes moved", nodesMoved, nodesMoved));
		}
		if (!bagNodesLeft.isEmpty()) {
			resultSummary.addNote(trn("{0} node created", "{0} nodes created", bagNodesLeft.size(), bagNodesLeft.size()));
		}

		// Remove nodes that are not used anymore
		// - not allowed when tagged with something (not uninteresting)
		// - not allowed when part of other ways (building:part, other house, fence, etc)
		int nodesRemoved = 0;
		int nodesInOtherWays = 0;
		int nodesTagged = 0;
		for (Node osmNodeLeft : osmNodesLeft) {
			List<Way> isInsideWays = osmNodeLeft.getParentWays();
			isInsideWays.remove(osmWay);
			if (!isInsideWays.isEmpty()) {
				// Cannot remove node, is inside another way
				nodesInOtherWays++;
				continue;
			}

			if (osmNodeLeft.isTagged()) {
				// Node itself has tags, cannot remove
				nodesTagged++;
				continue;
			}

			nodesRemoved++;
			updateBuildingCommands.add(new DeleteCommand(osmNodeLeft));
		}
		if (nodesInOtherWays > 0) {
			resultSummary.addWarning(trn("{0} node kept because it is part of another way", "{0} nodes kept because they are part of other ways", nodesInOtherWays, nodesInOtherWays));
		}
		if (nodesTagged > 0) {
			resultSummary.addWarning(trn("{0} node kept because it has important tags", "{0} nodes kept because they have important tags", nodesTagged, nodesTagged));
		}
		if (nodesRemoved > 0) {
			resultSummary.addNote(trn("{0} node removed", "{0} nodes removed", nodesRemoved, nodesRemoved));
		}

		// Update start_date tag
		Command updateStartDateCommand = computeStartDateUpdate();
		if (updateStartDateCommand != null) {
			updateBuildingCommands.add(updateStartDateCommand);
		}

		// Detect no updates case
		if (updateBuildingCommands.isEmpty()) {
			resultSummary.addNote(tr("Building is already up-to-date"));
			return true;
		}

		// Execute the changes
		Command combinedCommand = SequenceCommand.wrapIfNeeded(tr("BAG update of {0}", bagRef), updateBuildingCommands);
		UndoRedoHandler.getInstance().add(combinedCommand);
		return true;
	}

	private static void printNodePairs(Map<Node, Node> fromTo) {
		if (!BagQuickPlugin.DEBUG) {
			return;
		}

		BagQuickPlugin.debug("Resulting node pairs:");
		for (Map.Entry<Node, Node> entry : fromTo.entrySet()) {
			BagQuickPlugin.debug("    Pair:");
			Node bagNode = entry.getValue();
			BagQuickPlugin.debug("        BAG node: {0} {1}", bagNode.get("name"), bagNode.getCoor());
			Node osmNode = entry.getKey();
			BagQuickPlugin.debug("        OSM node: {0} {1}", osmNode.get("name"), osmNode.getCoor());
			BagQuickPlugin.debug("        distance: {0}", bagNode.getCoor().distance(osmNode.getCoor()));
		}
	}

	/**
	 * Create the given Way in the OSM layer
	 */
	private boolean createNewBuilding() {
		Logging.info("Creating a new BAG way");
		String bagRef = this.bagWay.get("ref:bag");
		Collection<Command> wayAndNodesCommands = new LinkedList<>();

		// Create a way based on the source coordinates
		Way osmWay = new Way();
		for (Node bagNode : this.bagWay.getNodes()) {
			Node osmNode = new Node();
			osmNode.setCoor(bagNode.getCoor());
			osmWay.addNode(osmNode);
			wayAndNodesCommands.add(new AddCommand(osmDataSet, osmNode));
			// TODO: double check this creates a closed way, or does it duplicate the start/end node?
		}
		wayAndNodesCommands.add(new AddCommand(osmDataSet, osmWay));

		// Execute adding way+nodes
		Command wayAndNodesCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: way+nodes: {0}", bagRef), wayAndNodesCommands);
		UndoRedoHandler.getInstance().add(wayAndNodesCommand);

		// Apply all tags of the BAG way to the OSM way (at least building/ref:bag/source/source:date/start_date)
		// TODO: do this for existing buildings as well  to fix up tags
		Collection<Command> tagsCommands = new LinkedList<>();
		for (Map.Entry<String, String> sourceTag : this.bagWay.getKeys().entrySet()) {
			// Ignore tags prefixed with |ODS, those are only meant as background information
			if (sourceTag.getKey().startsWith("|ODS")) {
				continue;
			}

			Logging.info("    adding tag {0}={1}", sourceTag.getKey(), sourceTag.getValue());
			tagsCommands.add(new ChangePropertyCommand(osmWay, sourceTag.getKey(), sourceTag.getValue()));
		}

		// TODO: when building=contruction warn about importing it? Or just use building=yes? Check with forum/discord

		// Execute the changes
		Command tagsCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: add tags: {0}", bagRef), tagsCommands);
		UndoRedoHandler.getInstance().add(tagsCommand);

		// Notify about the result
		resultSummary.addNote(tr("New BAG building imported with {0} nodes", bagWay.getNodesCount())); // TODO: maybe use setHeader() or so
		for (String osmWayTag : osmWay.getKeys().keySet()) {
			resultSummary.addNote(tr(osmWayTag + "=" + osmWay.get(osmWayTag)));
		}
		return true;
	}

	/** Compute update for the start_date tag */
	private Command computeStartDateUpdate() {
		// Check if the tag is present
		if (!bagWay.hasTag("start_date")) {
			return null;
		}

		// Check if the value makes sense
		String bagStartDate = bagWay.get("start_date");
		if (bagStartDate == null || bagStartDate.isEmpty()) {
			return null;
		}

		// Check the target value
		String osmStartDate = osmWay.get("start_date");
		if (bagStartDate.equals(osmStartDate)) {
			return null;
		}

		// Apply the tag change
		if (osmStartDate == null) {
			resultSummary.addNote(tr("start_date={0} added", bagStartDate));
		} else {
			resultSummary.addNote(tr("start_date={0} set, was previously {1}", bagStartDate, osmStartDate));
		}
		return new ChangePropertyCommand(osmWay, "start_date", bagStartDate);
	}


	/** Search box around the clicked point */
	private BBox getSearchBox() {
		int snapDistance = 100;
		return new BBox(
			this.mapView.getLatLon(clickedPoint.x - snapDistance, clickedPoint.y - snapDistance),
			this.mapView.getLatLon(clickedPoint.x + snapDistance, clickedPoint.y + snapDistance)
		);
	}

	private void printWayList(Collection<Way> ways) {
		for (Way way : ways) {
			BagQuickPlugin.debug("    way:"+way.getId());
			BagQuickPlugin.debug("        area="+way.isArea());
			BagQuickPlugin.debug("        containsClickedPoint="+way.getBBox().bounds(this.clickedLatLon));
			BagQuickPlugin.debug("        building="+way.get("building"));
			BagQuickPlugin.debug("        ref:bag="+way.get("ref:bag"));
		}
	}
}