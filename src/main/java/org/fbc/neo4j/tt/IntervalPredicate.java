package org.fbc.neo4j.tt;

import org.joda.time.Interval;
import org.joda.time.ReadableInterval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.traversal.BranchState;
import org.neo4j.helpers.Predicate;

/**
 * A filter predicate for a Iterable of Relationship.
 * Checks if {@link org.joda.time.ReadableInterval} of branchState has an overlap with to/from properties of current relationship.
 * <p/>
 * <p/>
 * kk ready.
 *
 * @author Stefan Armbruster
 */
public class IntervalPredicate implements Predicate<Relationship> {

    private final BranchState<ReadableInterval> state;

    private final String fromPropertyName;
    private final String toPropertyName;
    private final DateTimeFormatter dateTimeFormat;

    public IntervalPredicate(BranchState<ReadableInterval> state, String fromPropertyName, String toPropertyName, String datePattern) {
        this.state = state;
        this.fromPropertyName = fromPropertyName;
        this.toPropertyName = toPropertyName;
        this.dateTimeFormat = DateTimeFormat.forPattern(datePattern);
    }

    @Override
    public boolean accept(Relationship relationship) {
        ReadableInterval overlap = getIntervalFromRelationship(relationship).overlap(state.getState());
        state.setState(overlap);
        return overlap != null;
    }

    private Interval getIntervalFromRelationship(Relationship relationship) {
        String from = (String) relationship.getProperty(fromPropertyName);
        String to = (String) relationship.getProperty(toPropertyName);
        long fromLong = dateTimeFormat.parseMillis(from);
        long toLong = dateTimeFormat.parseMillis(to);
        // swap boundaries to prevent "The end instant must be greater or equal to the start" since some rels seem to have to/from exchanged.
        if (fromLong > toLong) {
            long tmp = fromLong;
            fromLong = toLong;
            toLong = tmp;
        }
        return new Interval(fromLong, toLong);
    }
}