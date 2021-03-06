package org.fbc.neo4j.tt;

import org.joda.time.Interval;
import org.joda.time.ReadableInterval;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.InitialBranchState;
import org.neo4j.graphdb.traversal.TraversalBranch;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.tooling.GlobalGraphOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.*;

@Path("/")
public class TimeTunnelsExtension {
    public static Logger log = LoggerFactory.getLogger("org.fbc");

    public static String FROM_PROPERTY_NAME = "dateFrom";
    public static String TO_PROPERTY_NAME = "dateTo";
    public static String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSSS";
    public static String IDENTITY_PROPERTY = "symbol";
    public static Label MS_LABEL = DynamicLabel.label("MS");
    public static Label AGENT_LABEL = DynamicLabel.label("AGENT");

    public enum RelTypes implements RelationshipType {
        HAS_MANAGER,
        HAS_WORKER,
        SUPERIOR,
    }

    @Context
    GraphDatabaseService graphDatabaseService;

    private static TraversalDescription timeTunnelTraversalForMs = null;
    private static TraversalDescription timeTunnelTraversalForAgent = null;

    // XXX debug
    private static boolean doReturnPath = true;


    /**
     * Return a version number at runtime.
     * @return a string representing version number
     */
    @GET
    @Path("/version")
    public String version() {
        log.info(("version"));
        // XXX Doesn't work
        String iv = getClass().getPackage().getImplementationVersion();
        getClass().getPackage().getImplementationTitle();
        String manual = "0.15.00-SNAPSHOT";
        String result = String.format("manual: %s, implementationVersion: %s", manual, iv);
        return result;
    }

    @GET
    @Path("/warmup")
    public String warmUp() {
        log.info(("warmup"));
        Node start;
        try (Transaction tx = graphDatabaseService.beginTx()) {
            GlobalGraphOperations ggo = GlobalGraphOperations.at(graphDatabaseService);

            Iterable<Node> nodes = ggo.getAllNodes();
            for (Node n : nodes) {
                n.getPropertyKeys();
                for (Relationship relationship : n.getRelationships()) {
                    start = relationship.getStartNode();
                }
            }

            Iterable<Relationship> relations = ggo.getAllRelationships();
            for (Relationship r : relations) {
                r.getPropertyKeys();
                start = r.getStartNode();
            }
        }
        return "Warmed up and ready to go!";
    }

    @GET
    @Path("from/{startingNodeType}/{symbol}")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<Map<String, Object>> findTunnels(
            @PathParam("startingNodeType") String startingNodeType,
            @PathParam("symbol") String symbol
    ) {
        log.info(String.format("findTunnels from %s, symbol=%s", startingNodeType, symbol));
        Collection<Map<String, Object>> result;


        try (Transaction tx = graphDatabaseService.beginTx()) {
            result = findTunnelsFor(startingNodeType, symbol);
            return result;
        }
    }

    /**
     * Check traversal time for every ms/agent.
     * @param startingNodeType a type of nodes for wich time tunnels are found (ms/agent).
     * @return a collection of (symbol, time, count) objects. symbol: starting node, time: execution time, count: number of paths found.
     */
    @GET
    @Path("measure/{startingNodetype}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public Collection<Map<String, Object>> measure(
            @PathParam("startingNodetype") String startingNodeType) {
        log.warn(String.format("measure traversal from %s", startingNodeType));

        Collection<Map<String, Object>> result = new ArrayList<>();

        ResourceIterable<Node> startingNodes;
        try (Transaction tx = graphDatabaseService.beginTx()) {
            // find all nodes of given type
            startingNodes = this.getStartingNodes(startingNodeType, null);

            for (Node startingNode : startingNodes) {
                String symbol = (String) startingNode.getProperty("symbol");
                log.warn(String.format("measuring tunnel finding for %s", symbol));
                long startTime = System.currentTimeMillis();

                Collection<Map<String, Object>> paths = findTunnelsFor(startingNodeType, symbol);
                long endTime = System.currentTimeMillis();

                Map<String, Object> one = new HashMap<>();
                one.put("symbol", symbol);
                one.put("time", endTime - startTime);
                one.put("count", paths.size());
                result.add(one);
            }

        } catch (Exception e) {
            log.error("Error:" + e);
        }
        return result;
    }


    /**
     * Finds time tunnels for given srtating node type and its symbol (id).
     * @param startingNodeType a type of nodes for wich time tunnels are found (ms/agent).
     * @param symbol identifier of starting node
     * @return returns (symbol, labels, from, to) objects for each tunnel found
     */
    private Collection<Map<String, Object>> findTunnelsFor(String startingNodeType, String symbol) {
        log.info(String.format("findTunnelsFor %s %s", startingNodeType, symbol));

        Collection<Map<String, Object>> result;
        long startTime = System.currentTimeMillis();

        // determine traverser (based on invocation url)
        TraversalDescription td = this.getTraversalDescriptionForMs();
        if ("agent".equalsIgnoreCase(startingNodeType)) {
            td = this.getTraversalDescriptionForAgent();
        }

        // find starting node(s)
        ResourceIterable<Node> startingNodes = this.getStartingNodes(startingNodeType, symbol);
        long startingNodesTime = System.currentTimeMillis();
        log.debug("after find starting nodes in " + (startingNodesTime - startTime));

        // find the paths
        ResourceIterable<org.neo4j.graphdb.Path> paths = td.traverse(startingNodes);
        long afterTraverseTime = System.currentTimeMillis();
        log.debug(String.format("after traverse in %d", afterTraverseTime - startingNodesTime));

        // collect results
        result = this.collectTraversalResults(paths);

        long endTime = System.currentTimeMillis();
        log.debug(String.format("after iterate through paths in %d", endTime - afterTraverseTime));

        log.info(String.format("finished in %d", endTime - startTime));
        return result;
    }

    /**
     * Method that gets all the traversed paths and builds result collection.
     *
     * @param paths Paths taken from traversal
     * @return collection o objects (map) with labels, identity, time tunnel from, to dates
     */
    private Collection<Map<String, Object>> collectTraversalResults(ResourceIterable<org.neo4j.graphdb.Path> paths) {
        Collection<Map<String, Object>> result = new ArrayList<>();
        // consume traversal and build return data structure
        for (org.neo4j.graphdb.Path p : paths) {
            TraversalBranch path = (TraversalBranch) p; // need to cast here to get access to state variable

            Map<String, Object> map = new HashMap<>();
            map.put("labels", getLabels(path.endNode()));
            map.put(IDENTITY_PROPERTY, path.endNode().getProperty(IDENTITY_PROPERTY, null));

            ReadableInterval interval = (ReadableInterval) path.state();
            map.put(FROM_PROPERTY_NAME, interval.getStart().toDateTime().toString(DATE_PATTERN));
            map.put(TO_PROPERTY_NAME, interval.getEnd().toDateTime().toString(DATE_PATTERN));


            if (doReturnPath) {
                map.put("path", org.neo4j.graphdb.traversal.Paths.defaultPathToString(path));
            }
            result.add(map);
        }

        return result;
    }

    /**
     * Find starting node for the traversal.
     * If the symbol is provided, it looks for nodes of given tyle (label) andy symbol.
     * @param startingNodeType type of the node to find
     * @param symbol identity of the node
     * @return nodes matching the search criteria
     */
    private ResourceIterable<Node> getStartingNodes(String startingNodeType, String symbol) {
        // determine starting node label
        Label label = MS_LABEL;
        if ("agent".equalsIgnoreCase(startingNodeType)) {
            label = AGENT_LABEL;
        }

        ResourceIterable<Node> startingNodes;
        if (symbol != null && symbol.length() > 0) {
            startingNodes = graphDatabaseService.findNodesByLabelAndProperty(
                    label, IDENTITY_PROPERTY, symbol
            );
        } else {
            GlobalGraphOperations ggo = GlobalGraphOperations.at(graphDatabaseService);
            startingNodes = ggo.getAllNodesWithLabel(label);
        }

        return startingNodes;
    }

    /**
     * Holds logic of reading the node labels.
     * @param node a node to read the labels from
     * @return list of node's label names
     */
    private List<String> getLabels(Node node) {
        List<String> result = new ArrayList<>();
        for (Label label : node.getLabels()) {
            result.add(label.name());
        }
        return result;
    }

    /**
     * Generate traversal description (if not already generated) for searching time tunnels starting from MS.
     * @return traversal description for (ms:MS)<-[r0:HAS_MANAGER]-(a1:AGENT)-[r1:SUPERIOR*0..]->(a2:AGENT)-[:WORKER*0..1]->(w:WORKER)
     */
    private TraversalDescription getTraversalDescriptionForMs() {
        if (timeTunnelTraversalForMs == null) {
            // defining path expander
            TimeTunnelPathExpander timeTunnelPathExpander = new TimeTunnelPathExpander(FROM_PROPERTY_NAME, TO_PROPERTY_NAME, DATE_PATTERN);
            log.info("step 1");
            // should traverse: match (ms:MS)<-[r0:HAS_MANAGER]-(a1:AGENT)-[r1:SUPERIOR*0..]->(a2:AGENT)-[:WORKER*0..1]->(w:WORKER)
            timeTunnelPathExpander.add(RelTypes.HAS_MANAGER, Direction.INCOMING);
            timeTunnelPathExpander.add(RelTypes.HAS_WORKER, Direction.OUTGOING);
            timeTunnelPathExpander.add(RelTypes.SUPERIOR, Direction.OUTGOING);
            log.info("step 2");
            // create traversal description
            timeTunnelTraversalForMs = graphDatabaseService.traversalDescription()
                    .expand(timeTunnelPathExpander, new InitialBranchState.State<ReadableInterval>(
                            new Interval(0, Long.MAX_VALUE), null
                    ))
                    .evaluator(new HasOverlapPathEvaluator())       // add all nodes to resultset having time tunnel
                    .evaluator(Evaluators.excludeStartPosition());
        }
        return timeTunnelTraversalForMs;
    }

    /**
     * Generate traversal description (if not already generated) for searching time tunnels starting from AGENT.
     * @return traversal description for (a1:AGENT)-[r1:SUPERIOR*0..]->(a2:AGENT)-[:WORKER*0..1]->(w:WORKER)
     */
    private TraversalDescription getTraversalDescriptionForAgent() {
        if (timeTunnelTraversalForAgent == null) {
            // defining path expander
            TimeTunnelPathExpander timeTunnelPathExpander = new TimeTunnelPathExpander(FROM_PROPERTY_NAME, TO_PROPERTY_NAME, DATE_PATTERN);
            log.info("step 1");
            // should traverse: match (a1:AGENT)-[r1:SUPERIOR*0..]->(a2:AGENT)-[:WORKER*0..1]->(w:WORKER)
            timeTunnelPathExpander.add(RelTypes.HAS_WORKER, Direction.OUTGOING);
            timeTunnelPathExpander.add(RelTypes.SUPERIOR, Direction.OUTGOING);
            log.info("step 2");
            // create traversal description
            timeTunnelTraversalForAgent = graphDatabaseService.traversalDescription()
                    .expand(timeTunnelPathExpander, new InitialBranchState.State<ReadableInterval>(
                            new Interval(0, Long.MAX_VALUE), null
                    ))
                    .evaluator(new HasOverlapPathEvaluator())       // add all nodes to resultset having time tunnel
                    .evaluator(Evaluators.excludeStartPosition());
        }
        return timeTunnelTraversalForAgent;
    }
}