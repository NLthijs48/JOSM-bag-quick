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

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static me.wiefferink.bagquick.BagQuickPlugin.debug;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

// TODO:
// - extract findOsmWay and findBagWay to a Search class
// - extract node pairing algorithms to separate classes implementing a certain interface

/**
 * Represents a 1-click BAG to OSM building update run
 * - structures the different steps of the update
 * - collects results of the update
 */
public class BuildingUpdate {

	/**
	 * Up to this number of nodes, do slow pair matching that is O(n^2)
	 * - For higher node counts fall back to a simpler algorithm that only matches nodes that are close to each other
	 */
	private static final int MAX_SLOW_PAIRING_NODE_COUNT = 25;

	/**
	 * Number of meters nodes are allowed to differ from BAG before being updated
	 * - 10cm is close enough, consider that accurate
	 */
	private static final double DESIRED_PRECISION_METERS = 0.1;

	/** Maximum distance existing nodes should be moved around when already tagged with something */
	private static final double MAX_NODE_MOVE_METERS_TAGGED = 0.01;

	/** Maximum distance existing nodes should be moved around when already tagged with something */
	private static final double MAX_NODE_MOVE_METERS_UNTAGGED = 5;

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
	private final ResultSummary resultSummary;

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
		debug("BuildingUpdate.execute()");
		debug("clicked LatLon={0}", clickedLatLon);

		// Check that the BAG ODS and BAG OSM layers are present
		if (!checkLayers()) {
			return false;
		}

		// Find the clicked way on the BAG ODS layer
		if (!findBagWay()) {
			return false;
		}

		// Show the BAG way as selected
		bagDataSet.setSelected(this.bagWay);

		// Find the Way on the OSM layer
		if (!findOsmWay()) {
			return createNewBuilding();
		}

		// Show the OSM way as selected
		osmDataSet.setSelected(this.osmWay);

		debug("    found OSM way: {0}", osmWay);
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
		debug("findBagWay() {0} results:", bagSearchWays.size());
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

		debug("findBagWay() ref:bag="+ bagRef);
		debug("    found BAG way: "+bagWay);
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
		debug("findOsmWay() {0} search results:", osmSearchWays.size());
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

		// Confirm notes before doing anything else
		if (!confirmBuildingNotes()) {
			return false;
		}

		// Setup node lists to work with
		Map<Node, Node> bagToOsmNode = new HashMap<>();
		int bagNodeCount = bagWay.getNodesCount();
		List<Node> bagNodes = bagWay.getNodes();
		Set<Node> bagNodesLeft = new HashSet<>(bagNodes);
		int osmNodeCount = osmWay.getNodesCount();
		List<Node> osmNodes = osmWay.getNodes();
		Set<Node> osmNodesLeft = new HashSet<>(osmNodes);

		boolean useSlow = bagNodeCount < MAX_SLOW_PAIRING_NODE_COUNT && osmNodeCount < MAX_SLOW_PAIRING_NODE_COUNT;

		// Match BAG nodes to OSM nodes in a way that moves them as little as possible
		// - code roughly based on the ReplaceBuilding action
		if (useSlow) {  // use robust, but slower assignment
			int N = Math.max(bagNodeCount, osmNodeCount);
			double[][] cost = new double[N][N];
			for (int i = 0; i < N; i++) {
				for (int j = 0; j < N; j++) {
					cost[i][j] = Double.MAX_VALUE;
				}
			}

			for (int bagNodeIndex = 0; bagNodeIndex < bagNodeCount; bagNodeIndex++) {
				for (int osmNodeIndex = 0; osmNodeIndex < osmNodeCount; osmNodeIndex++) {
					Node osmNode = osmNodes.get(osmNodeIndex);

					// Longer maximum distance when the node has no tags or other parent ways
					// - idea is to not move around nodes too much
					double maxDistance = (osmNode.isTagged() || osmNode.getParentWays().size() > 1) ? MAX_NODE_MOVE_METERS_TAGGED : MAX_NODE_MOVE_METERS_UNTAGGED;

					double distance = bagNodes.get(bagNodeIndex).getCoor().greatCircleDistance(osmNode.getCoor());
					if (distance >= maxDistance) {
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

		// Quick backup algorithm
		if (!useSlow) {
            for (Node bagNode : bagNodes) {
				// Skip when already done (first and last Node will be the same one)
				if (!bagNodesLeft.contains(bagNode)) {
					continue;
				}

                Node nearestOsmNode = findNearestNode(bagNode, osmNodesLeft);
                if (nearestOsmNode != null) {
					osmNodesLeft.remove(nearestOsmNode);
					bagNodesLeft.remove(bagNode);
					bagToOsmNode.put(bagNode, nearestOsmNode);
				}
            }
		}

		// debug logging
		printNodePairs(bagToOsmNode);
		debug("Leftover BAG nodes:");
		for (Node bagNodeLeft : bagNodesLeft) {
			debug("    {0} {1}", bagNodeLeft.get("name"), bagNodeLeft.getCoor());
		}
		debug("Leftover OSM nodes:");
		for (Node osmNodeLeft : osmNodesLeft) {
			debug("    {0} {1}", osmNodeLeft.get("name"), osmNodeLeft.getCoor());
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
			if (bagCoor.greatCircleDistance(osmNode.getCoor()) < DESIRED_PRECISION_METERS) {
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

		// Compute tag updates
		for (Map.Entry<String, String> bagTagEntry : this.bagWay.getKeys().entrySet()) {
			// Ignore tags prefixed with |ODS, those are only meant as background information
			String tagName = bagTagEntry.getKey();
			if (tagName.startsWith("|ODS")) {
				continue;
			}

			// Skip updating building, might be more specific in OSM already
			// TODO: improve this logic
			if (tagName.equals("building")) {
				continue;
			}

			Command tagUpdateCommand = computeTagUpdate(tagName);
			if (tagUpdateCommand != null) {
				updateBuildingCommands.add(tagUpdateCommand);
			}
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

	/**
	 * Find the nearest node in a set
	 * @param targetNode The node to get closest to
	 * @param optionNodes The options to choose from
	 * @return null if there is no Node close enough, otherwise the closest node
	 */
	static Node findNearestNode(Node targetNode, Collection<Node> optionNodes) {
		// Check exact match
		if (optionNodes.contains(targetNode)) {
			return targetNode;
		}

		// Find nearest
		// - use cheap square distance calculation here
		Node nearest = null;
		double nearestDistance = 0;
		LatLon targetCoor = targetNode.getCoor();
		for (Node optionNode : optionNodes) {
			double optionNodeDistance = optionNode.getCoor().distanceSq(targetCoor);
			if (nearest == null || optionNodeDistance < nearestDistance) {
				nearestDistance = optionNodeDistance;
				nearest = optionNode;
			}
		}

		// Check if it matches the required precision
		// - only use 'expensive' real distance in meters here once
		// - consider all nodes 'tagged' because this algoritm should not move them around much
		if (targetCoor.greatCircleDistance(nearest.getCoor()) >= MAX_NODE_MOVE_METERS_TAGGED) {
			return null;
		}

		return nearest;
	}

	/** If there are notes on the building, let the user confirm before doing updates */
	private boolean confirmBuildingNotes() {
		// Test out dialog to confirm
		Map<String, String> noteTags = new HashMap<>();
		for (String noteTag : Arrays.asList("note", "note:bag", "fixme")) {
			if (!osmWay.hasTag(noteTag)) {
				continue;
			}

			String note = osmWay.get(noteTag);
			if (note == null || note.isEmpty()) {
				continue;
			}

			noteTags.put(noteTag, note);
		}
		if (noteTags.isEmpty()) {
			// No notes to worry about
			return true;
		}

		debug("Constructing note dialog");
		NoteConfirmationDialog dialog = new NoteConfirmationDialog(noteTags);
		dialog.setVisible(true);
		if (dialog.isCanceled()) {
			resultSummary.failed(tr("Building update canceled because of notes"));
			return false;
		}
		debug("dialog result: " + dialog.isCanceled());
		return !dialog.isCanceled();
	}

	private static void printNodePairs(Map<Node, Node> fromTo) {
		if (!BagQuickPlugin.DEBUG) {
			return;
		}

		debug("Resulting node pairs:");
		for (Map.Entry<Node, Node> entry : fromTo.entrySet()) {
			debug("    Pair:");
			Node bagNode = entry.getValue();
			debug("        BAG node: {0} {1}", bagNode.get("name"), bagNode.getCoor());
			Node osmNode = entry.getKey();
			debug("        OSM node: {0} {1}", osmNode.get("name"), osmNode.getCoor());
			debug("        distance: {0} m", bagNode.getCoor().greatCircleDistance(osmNode.getCoor()));
		}
	}

	/**
	 * Create the given Way in the OSM layer
	 */
	private boolean createNewBuilding() {
		debug("Creating a new BAG way");
		String bagRef = this.bagWay.get("ref:bag");
		Collection<Command> wayAndNodesCommands = new LinkedList<>();

		// Add all nodes to the Way based on the source coordinates
		Way osmWay = new Way();
		int nodeIndex = 0;
		for (Node bagNode : this.bagWay.getNodes()) {
			// Detect last node: add the first Node again to close the Way
			// (prevent creating a duplicate node for the last one in the same spot as the first one)
			if (nodeIndex == this.bagWay.getNodesCount() - 1) {
				osmWay.addNode(osmWay.firstNode());
				break;
			}

			Node osmNode = new Node();
			osmNode.setCoor(bagNode.getCoor());
			osmWay.addNode(osmNode);
			wayAndNodesCommands.add(new AddCommand(osmDataSet, osmNode));

			nodeIndex++;
		}

		// Add the way itself
		wayAndNodesCommands.add(new AddCommand(osmDataSet, osmWay));

		// Execute adding way+nodes
		Command wayAndNodesCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: way+nodes: {0}", bagRef), wayAndNodesCommands);
		UndoRedoHandler.getInstance().add(wayAndNodesCommand);

		// Select the new OSM way
		osmDataSet.setSelected(osmWay);

		// TODO: maybe track this as header or so to make bold/bigger?
		resultSummary.addNote(tr("New BAG building imported with {0} nodes", bagWay.getNodesCount()));

		// Apply all tags of the BAG way to the OSM way (at least building/ref:bag/source/source:date/start_date)
		Collection<Command> tagsCommands = new LinkedList<>();
		for (Map.Entry<String, String> bagTagEntry : this.bagWay.getKeys().entrySet()) {
			// Ignore tags prefixed with |ODS, those are only meant as background information
			if (bagTagEntry.getKey().startsWith("|ODS")) {
				continue;
			}

			debug("    adding tag {0}={1}", bagTagEntry.getKey(), bagTagEntry.getValue());
			resultSummary.addNote(tr("{0}={1} added", bagTagEntry.getKey(), bagTagEntry.getValue()));
			tagsCommands.add(new ChangePropertyCommand(osmWay, bagTagEntry.getKey(), bagTagEntry.getValue()));
		}

		// TODO: when building=contruction warn about importing it? Or just use building=yes? Check with forum/discord

		// Execute the changes
		Command tagsCommand = SequenceCommand.wrapIfNeeded(tr("Create new BAG building: add tags: {0}", bagRef), tagsCommands);
		UndoRedoHandler.getInstance().add(tagsCommand);

		// Notify about the result
		return true;
	}

	/**
	 * Compute update for a tag
	 * @return null when the BAG does not contain the tag, or the target already contains the same value, otherwise a command to add/update it
	 */
	private Command computeTagUpdate(String tag) {
		// Check if the tag is present
		if (!bagWay.hasTag(tag)) {
			return null;
		}

		// Check if the value makes sense
		String bagTagValue = bagWay.get(tag);
		if (bagTagValue == null || bagTagValue.isEmpty()) {
			return null;
		}

		// Check the target value
		String osmTagValue = osmWay.get(tag);
		if (bagTagValue.equals(osmTagValue)) {
			return null;
		}

		// Apply the tag change
		if (osmTagValue == null) {
			resultSummary.addNote(tr("{0}={1} added", tag, bagTagValue));
		} else {
			resultSummary.addNote(tr("{0}={1} set, was previously {2}", tag, bagTagValue, osmTagValue));
		}
		return new ChangePropertyCommand(osmWay, tag, bagTagValue);
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
			debug("    way:"+way.getId());
			debug("        area="+way.isArea());
			debug("        containsClickedPoint="+way.getBBox().bounds(this.clickedLatLon));
			debug("        building="+way.get("building"));
			debug("        ref:bag="+way.get("ref:bag"));
		}
	}
}
