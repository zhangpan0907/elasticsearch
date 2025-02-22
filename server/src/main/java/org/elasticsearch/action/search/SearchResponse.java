/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

import org.apache.lucene.search.TotalHits;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.common.xcontent.ChunkedToXContentObject;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestActions;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileResults;
import org.elasticsearch.search.profile.SearchProfileShardResult;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParser.Token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.elasticsearch.action.search.ShardSearchFailure.readShardSearchFailure;
import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

/**
 * A response of a search request.
 */
public class SearchResponse extends ActionResponse implements ChunkedToXContentObject {

    private static final ParseField SCROLL_ID = new ParseField("_scroll_id");
    private static final ParseField POINT_IN_TIME_ID = new ParseField("pit_id");
    private static final ParseField TOOK = new ParseField("took");
    private static final ParseField TIMED_OUT = new ParseField("timed_out");
    private static final ParseField TERMINATED_EARLY = new ParseField("terminated_early");
    private static final ParseField NUM_REDUCE_PHASES = new ParseField("num_reduce_phases");

    private final SearchResponseSections internalResponse;
    private final String scrollId;
    private final String pointInTimeId;
    private final int totalShards;
    private final int successfulShards;
    private final int skippedShards;
    private final ShardSearchFailure[] shardFailures;
    private final Clusters clusters;
    private final long tookInMillis;

    public SearchResponse(StreamInput in) throws IOException {
        super(in);
        internalResponse = new InternalSearchResponse(in);
        totalShards = in.readVInt();
        successfulShards = in.readVInt();
        int size = in.readVInt();
        if (size == 0) {
            shardFailures = ShardSearchFailure.EMPTY_ARRAY;
        } else {
            shardFailures = new ShardSearchFailure[size];
            for (int i = 0; i < shardFailures.length; i++) {
                shardFailures[i] = readShardSearchFailure(in);
            }
        }
        clusters = new Clusters(in);
        scrollId = in.readOptionalString();
        tookInMillis = in.readVLong();
        skippedShards = in.readVInt();
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_7_10_0)) {
            pointInTimeId = in.readOptionalString();
        } else {
            pointInTimeId = null;
        }
    }

    public SearchResponse(
        SearchResponseSections internalResponse,
        String scrollId,
        int totalShards,
        int successfulShards,
        int skippedShards,
        long tookInMillis,
        ShardSearchFailure[] shardFailures,
        Clusters clusters
    ) {
        this(internalResponse, scrollId, totalShards, successfulShards, skippedShards, tookInMillis, shardFailures, clusters, null);
    }

    public SearchResponse(
        SearchResponseSections internalResponse,
        String scrollId,
        int totalShards,
        int successfulShards,
        int skippedShards,
        long tookInMillis,
        ShardSearchFailure[] shardFailures,
        Clusters clusters,
        String pointInTimeId
    ) {
        this.internalResponse = internalResponse;
        this.scrollId = scrollId;
        this.pointInTimeId = pointInTimeId;
        this.clusters = clusters;
        this.totalShards = totalShards;
        this.successfulShards = successfulShards;
        this.skippedShards = skippedShards;
        this.tookInMillis = tookInMillis;
        this.shardFailures = shardFailures;
        assert skippedShards <= totalShards : "skipped: " + skippedShards + " total: " + totalShards;
        assert scrollId == null || pointInTimeId == null
            : "SearchResponse can't have both scrollId [" + scrollId + "] and searchContextId [" + pointInTimeId + "]";
    }

    public RestStatus status() {
        return RestStatus.status(successfulShards, totalShards, shardFailures);
    }

    public SearchResponseSections getInternalResponse() {
        return internalResponse;
    }

    /**
     * The search hits.
     */
    public SearchHits getHits() {
        return internalResponse.hits();
    }

    /**
     * Aggregations in this response. "empty" aggregations could be
     * either {@code null} or {@link InternalAggregations#EMPTY}.
     */
    public @Nullable Aggregations getAggregations() {
        return internalResponse.aggregations();
    }

    /**
     * Will {@link #getAggregations()} return non-empty aggregation results?
     */
    public boolean hasAggregations() {
        return getAggregations() != null && getAggregations() != InternalAggregations.EMPTY;
    }

    public Suggest getSuggest() {
        return internalResponse.suggest();
    }

    /**
     * Has the search operation timed out.
     */
    public boolean isTimedOut() {
        return internalResponse.timedOut();
    }

    /**
     * Has the search operation terminated early due to reaching
     * <code>terminateAfter</code>
     */
    public Boolean isTerminatedEarly() {
        return internalResponse.terminatedEarly();
    }

    /**
     * Returns the number of reduce phases applied to obtain this search response
     */
    public int getNumReducePhases() {
        return internalResponse.getNumReducePhases();
    }

    /**
     * How long the search took.
     */
    public TimeValue getTook() {
        return new TimeValue(tookInMillis);
    }

    /**
     * The total number of shards the search was executed on.
     */
    public int getTotalShards() {
        return totalShards;
    }

    /**
     * The successful number of shards the search was executed on.
     */
    public int getSuccessfulShards() {
        return successfulShards;
    }

    /**
     * The number of shards skipped due to pre-filtering
     */
    public int getSkippedShards() {
        return skippedShards;
    }

    /**
     * The failed number of shards the search was executed on.
     */
    public int getFailedShards() {
        return shardFailures.length;
    }

    /**
     * The failures that occurred during the search.
     */
    public ShardSearchFailure[] getShardFailures() {
        return this.shardFailures;
    }

    /**
     * If scrolling was enabled ({@link SearchRequest#scroll(org.elasticsearch.search.Scroll)}, the
     * scroll id that can be used to continue scrolling.
     */
    public String getScrollId() {
        return scrollId;
    }

    /**
     * Returns the encoded string of the search context that the search request is used to executed
     */
    public String pointInTimeId() {
        return pointInTimeId;
    }

    /**
     * If profiling was enabled, this returns an object containing the profile results from
     * each shard.  If profiling was not enabled, this will return null
     *
     * @return The profile results or an empty map
     */
    @Nullable
    public Map<String, SearchProfileShardResult> getProfileResults() {
        return internalResponse.profile();
    }

    /**
     * Returns info about what clusters the search was executed against. Available only in responses obtained
     * from a Cross Cluster Search request, otherwise <code>null</code>
     * @see Clusters
     */
    public Clusters getClusters() {
        return clusters;
    }

    @Override
    public Iterator<? extends ToXContent> toXContentChunked(ToXContent.Params params) {
        return Iterators.concat(
            ChunkedToXContentHelper.startObject(),
            this.innerToXContentChunked(params),
            ChunkedToXContentHelper.endObject()
        );
    }

    public Iterator<? extends ToXContent> innerToXContentChunked(ToXContent.Params params) {
        return Iterators.concat(
            ChunkedToXContentHelper.singleChunk(SearchResponse.this::headerToXContent),
            Iterators.single(clusters),
            internalResponse.toXContentChunked(params)
        );
    }

    public XContentBuilder headerToXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        if (scrollId != null) {
            builder.field(SCROLL_ID.getPreferredName(), scrollId);
        }
        if (pointInTimeId != null) {
            builder.field(POINT_IN_TIME_ID.getPreferredName(), pointInTimeId);
        }
        builder.field(TOOK.getPreferredName(), tookInMillis);
        builder.field(TIMED_OUT.getPreferredName(), isTimedOut());
        if (isTerminatedEarly() != null) {
            builder.field(TERMINATED_EARLY.getPreferredName(), isTerminatedEarly());
        }
        if (getNumReducePhases() != 1) {
            builder.field(NUM_REDUCE_PHASES.getPreferredName(), getNumReducePhases());
        }
        RestActions.buildBroadcastShardsHeader(
            builder,
            params,
            getTotalShards(),
            getSuccessfulShards(),
            getSkippedShards(),
            getFailedShards(),
            getShardFailures()
        );
        return builder;
    }

    public static SearchResponse fromXContent(XContentParser parser) throws IOException {
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser);
        parser.nextToken();
        return innerFromXContent(parser);
    }

    public static SearchResponse innerFromXContent(XContentParser parser) throws IOException {
        ensureExpectedToken(Token.FIELD_NAME, parser.currentToken(), parser);
        String currentFieldName = parser.currentName();
        SearchHits hits = null;
        Aggregations aggs = null;
        Suggest suggest = null;
        SearchProfileResults profile = null;
        boolean timedOut = false;
        Boolean terminatedEarly = null;
        int numReducePhases = 1;
        long tookInMillis = -1;
        int successfulShards = -1;
        int totalShards = -1;
        int skippedShards = 0; // 0 for BWC
        String scrollId = null;
        String searchContextId = null;
        List<ShardSearchFailure> failures = new ArrayList<>();
        Clusters clusters = Clusters.EMPTY;
        for (Token token = parser.nextToken(); token != Token.END_OBJECT; token = parser.nextToken()) {
            if (token == Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if (SCROLL_ID.match(currentFieldName, parser.getDeprecationHandler())) {
                    scrollId = parser.text();
                } else if (POINT_IN_TIME_ID.match(currentFieldName, parser.getDeprecationHandler())) {
                    searchContextId = parser.text();
                } else if (TOOK.match(currentFieldName, parser.getDeprecationHandler())) {
                    tookInMillis = parser.longValue();
                } else if (TIMED_OUT.match(currentFieldName, parser.getDeprecationHandler())) {
                    timedOut = parser.booleanValue();
                } else if (TERMINATED_EARLY.match(currentFieldName, parser.getDeprecationHandler())) {
                    terminatedEarly = parser.booleanValue();
                } else if (NUM_REDUCE_PHASES.match(currentFieldName, parser.getDeprecationHandler())) {
                    numReducePhases = parser.intValue();
                } else {
                    parser.skipChildren();
                }
            } else if (token == Token.START_OBJECT) {
                if (SearchHits.Fields.HITS.equals(currentFieldName)) {
                    hits = SearchHits.fromXContent(parser);
                } else if (Aggregations.AGGREGATIONS_FIELD.equals(currentFieldName)) {
                    aggs = Aggregations.fromXContent(parser);
                } else if (Suggest.NAME.equals(currentFieldName)) {
                    suggest = Suggest.fromXContent(parser);
                } else if (SearchProfileResults.PROFILE_FIELD.equals(currentFieldName)) {
                    profile = SearchProfileResults.fromXContent(parser);
                } else if (RestActions._SHARDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    while ((token = parser.nextToken()) != Token.END_OBJECT) {
                        if (token == Token.FIELD_NAME) {
                            currentFieldName = parser.currentName();
                        } else if (token.isValue()) {
                            if (RestActions.FAILED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                parser.intValue(); // we don't need it but need to consume it
                            } else if (RestActions.SUCCESSFUL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                successfulShards = parser.intValue();
                            } else if (RestActions.TOTAL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                totalShards = parser.intValue();
                            } else if (RestActions.SKIPPED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                skippedShards = parser.intValue();
                            } else {
                                parser.skipChildren();
                            }
                        } else if (token == Token.START_ARRAY) {
                            if (RestActions.FAILURES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                while ((token = parser.nextToken()) != Token.END_ARRAY) {
                                    failures.add(ShardSearchFailure.fromXContent(parser));
                                }
                            } else {
                                parser.skipChildren();
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else if (Clusters._CLUSTERS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    clusters = Clusters.fromXContent(parser);
                } else {
                    parser.skipChildren();
                }
            }
        }
        SearchResponseSections searchResponseSections = new SearchResponseSections(
            hits,
            aggs,
            suggest,
            timedOut,
            terminatedEarly,
            profile,
            numReducePhases
        );
        return new SearchResponse(
            searchResponseSections,
            scrollId,
            totalShards,
            successfulShards,
            skippedShards,
            tookInMillis,
            failures.toArray(ShardSearchFailure.EMPTY_ARRAY),
            clusters,
            searchContextId
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        internalResponse.writeTo(out);
        out.writeVInt(totalShards);
        out.writeVInt(successfulShards);

        out.writeVInt(shardFailures.length);
        for (ShardSearchFailure shardSearchFailure : shardFailures) {
            shardSearchFailure.writeTo(out);
        }
        clusters.writeTo(out);
        out.writeOptionalString(scrollId);
        out.writeVLong(tookInMillis);
        out.writeVInt(skippedShards);
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_7_10_0)) {
            out.writeOptionalString(pointInTimeId);
        }
    }

    @Override
    public String toString() {
        return Strings.toString(this);
    }

    /**
     * Holds info about the clusters that the search was executed on: how many in total, how many of them were successful
     * and how many of them were skipped and further details in a Map of Cluster objects
     * (when doing a cross-cluster search).
     */
    public static class Clusters implements ToXContentFragment, Writeable {

        public static final Clusters EMPTY = new Clusters(0, 0, 0);

        static final ParseField _CLUSTERS_FIELD = new ParseField("_clusters");
        static final ParseField TOTAL_FIELD = new ParseField("total");
        static final ParseField SUCCESSFUL_FIELD = new ParseField("successful");
        static final ParseField SKIPPED_FIELD = new ParseField("skipped");
        static final ParseField RUNNING_FIELD = new ParseField("running");
        static final ParseField PARTIAL_FIELD = new ParseField("partial");
        static final ParseField FAILED_FIELD = new ParseField("failed");
        static final ParseField DETAILS_FIELD = new ParseField("details");

        private final int total;
        private final int successful;   // not used for minimize_roundtrips=true; dynamically determined from clusterInfo map
        private final int skipped;      // not used for minimize_roundtrips=true; dynamically determined from clusterInfo map

        // key to map is clusterAlias on the primary querying cluster of a CCS minimize_roundtrips=true query
        // the Map itself is immutable after construction - all Clusters will be accounted for at the start of the search
        // updates to the Cluster occur by CAS swapping in new Cluster objects into the AtomicReference in the map.
        private final Map<String, AtomicReference<Cluster>> clusterInfo;

        // not Writeable since it is only needed on the (primary) CCS coordinator
        private transient Boolean ccsMinimizeRoundtrips;

        /**
         * For use with cross-cluster searches.
         * When minimizing roundtrips, the number of successful, skipped, running, partial and failed clusters
         * is not known until the end of the search and it the information in SearchResponse.Cluster object
         * will be updated as each cluster returns.
         * @param localIndices The localIndices to be searched - null if no local indices are to be searched
         * @param remoteClusterIndices mapping of clusterAlias -> OriginalIndices for each remote cluster
         * @param ccsMinimizeRoundtrips whether minimizing roundtrips for the CCS
         * @param skipUnavailablePredicate given a cluster alias, returns true if that cluster is skip_unavailable=true
         *                                 and false otherwise
         */
        public Clusters(
            @Nullable OriginalIndices localIndices,
            Map<String, OriginalIndices> remoteClusterIndices,
            boolean ccsMinimizeRoundtrips,
            Predicate<String> skipUnavailablePredicate
        ) {
            assert remoteClusterIndices.size() > 0 : "At least one remote cluster must be passed into this Cluster constructor";
            this.total = remoteClusterIndices.size() + (localIndices == null ? 0 : 1);
            assert total >= 1 : "No local indices or remote clusters passed in";
            this.successful = 0; // calculated from clusterInfo map for minimize_roundtrips
            this.skipped = 0;    // calculated from clusterInfo map for minimize_roundtrips
            this.ccsMinimizeRoundtrips = ccsMinimizeRoundtrips;
            Map<String, AtomicReference<Cluster>> m = new HashMap<>();
            if (localIndices != null) {
                String localKey = RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
                Cluster c = new Cluster(localKey, String.join(",", localIndices.indices()), false);
                m.put(localKey, new AtomicReference<>(c));
            }
            for (Map.Entry<String, OriginalIndices> remote : remoteClusterIndices.entrySet()) {
                String clusterAlias = remote.getKey();
                boolean skipUnavailable = skipUnavailablePredicate.test(clusterAlias);
                Cluster c = new Cluster(clusterAlias, String.join(",", remote.getValue().indices()), skipUnavailable);
                m.put(clusterAlias, new AtomicReference<>(c));
            }
            this.clusterInfo = Collections.unmodifiableMap(m);
        }

        /**
         * Used for searches that are either not cross-cluster.
         * For CCS minimize_roundtrips=true use {@code Clusters(OriginalIndices, Map<String, OriginalIndices>, boolean)}
         * @param total total number of clusters in the search
         * @param successful number of successful clusters in the search
         * @param skipped number of skipped clusters (skipped can only happen for remote clusters with skip_unavailable=true)
         */
        public Clusters(int total, int successful, int skipped) {
            // TODO: change assert to total == 1 or total = 0 - this should probably only be used for local searches now
            assert total >= 0 && successful >= 0 && skipped >= 0 && successful <= total
                : "total: " + total + " successful: " + successful + " skipped: " + skipped;
            assert skipped == total - successful : "total: " + total + " successful: " + successful + " skipped: " + skipped;
            this.total = total;
            this.successful = successful;
            this.skipped = skipped;
            this.ccsMinimizeRoundtrips = false;
            this.clusterInfo = Collections.emptyMap();  // will never be used if created from this constructor
        }

        public Clusters(StreamInput in) throws IOException {
            this.total = in.readVInt();
            int successfulTemp = in.readVInt();
            int skippedTemp = in.readVInt();
            if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_500_053)) {
                List<Cluster> clusterList = in.readCollectionAsList(Cluster::new);
                if (clusterList.isEmpty()) {
                    this.clusterInfo = Collections.emptyMap();
                    this.successful = successfulTemp;
                    this.skipped = skippedTemp;
                } else {
                    Map<String, AtomicReference<Cluster>> m = new HashMap<>();
                    clusterList.forEach(c -> m.put(c.getClusterAlias(), new AtomicReference<>(c)));
                    this.clusterInfo = Collections.unmodifiableMap(m);
                    this.successful = getClusterStateCount(Cluster.Status.SUCCESSFUL);
                    this.skipped = getClusterStateCount(Cluster.Status.SKIPPED);
                }
            } else {
                this.successful = successfulTemp;
                this.skipped = skippedTemp;
                this.clusterInfo = Collections.emptyMap();
            }
            int running = getClusterStateCount(Cluster.Status.RUNNING);
            int partial = getClusterStateCount(Cluster.Status.PARTIAL);
            int failed = getClusterStateCount(Cluster.Status.FAILED);
            this.ccsMinimizeRoundtrips = false;
            assert total >= 0 : "total is negative: " + total;
            assert total == successful + skipped + running + partial + failed
                : "successful + skipped + running + partial + failed is not equal to total. total: "
                    + total
                    + " successful: "
                    + successful
                    + " skipped: "
                    + skipped
                    + " running: "
                    + running
                    + " partial: "
                    + partial
                    + " failed: "
                    + failed;
        }

        private Clusters(Map<String, AtomicReference<Cluster>> clusterInfoMap) {
            assert clusterInfoMap.size() > 0 : "this constructor should not be called with an empty Cluster info map";
            this.total = clusterInfoMap.size();
            this.clusterInfo = clusterInfoMap;
            this.successful = getClusterStateCount(Cluster.Status.SUCCESSFUL);
            this.skipped = getClusterStateCount(Cluster.Status.SKIPPED);
            // should only be called if "details" section of fromXContent is present (for ccsMinimizeRoundtrips)
            this.ccsMinimizeRoundtrips = true;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(total);
            out.writeVInt(successful);
            out.writeVInt(skipped);
            if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_500_053)) {
                if (clusterInfo != null) {
                    List<Cluster> clusterList = clusterInfo.values().stream().map(AtomicReference::get).toList();
                    out.writeCollection(clusterList);
                } else {
                    out.writeCollection(Collections.emptyList());
                }
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            if (total > 0) {
                builder.startObject(_CLUSTERS_FIELD.getPreferredName());
                builder.field(TOTAL_FIELD.getPreferredName(), total);
                builder.field(SUCCESSFUL_FIELD.getPreferredName(), getClusterStateCount(Cluster.Status.SUCCESSFUL));
                builder.field(SKIPPED_FIELD.getPreferredName(), getClusterStateCount(Cluster.Status.SKIPPED));
                builder.field(RUNNING_FIELD.getPreferredName(), getClusterStateCount(Cluster.Status.RUNNING));
                builder.field(PARTIAL_FIELD.getPreferredName(), getClusterStateCount(Cluster.Status.PARTIAL));
                builder.field(FAILED_FIELD.getPreferredName(), getClusterStateCount(Cluster.Status.FAILED));
                if (clusterInfo.size() > 0) {
                    builder.startObject("details");
                    for (AtomicReference<Cluster> cluster : clusterInfo.values()) {
                        cluster.get().toXContent(builder, params);
                    }
                    builder.endObject();
                }
                builder.endObject();
            }
            return builder;
        }

        public static Clusters fromXContent(XContentParser parser) throws IOException {
            XContentParser.Token token = parser.currentToken();
            ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser);
            int total = -1;
            int successful = -1;
            int skipped = -1;
            int running = 0;    // 0 for BWC
            int partial = 0;    // 0 for BWC
            int failed = 0;     // 0 for BWC
            Map<String, AtomicReference<Cluster>> clusterInfoMap = new HashMap<>();
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if (Clusters.TOTAL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        total = parser.intValue();
                    } else if (Clusters.SUCCESSFUL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        successful = parser.intValue();
                    } else if (Clusters.SKIPPED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        skipped = parser.intValue();
                    } else if (Clusters.RUNNING_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        running = parser.intValue();
                    } else if (Clusters.PARTIAL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        partial = parser.intValue();
                    } else if (Clusters.FAILED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        failed = parser.intValue();
                    } else {
                        parser.skipChildren();
                    }
                } else if (token == Token.START_OBJECT) {
                    if (Clusters.DETAILS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        String currentDetailsFieldName = null;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentDetailsFieldName = parser.currentName();  // cluster alias
                            } else if (token == Token.START_OBJECT) {
                                Cluster c = Cluster.fromXContent(currentDetailsFieldName, parser);
                                clusterInfoMap.put(currentDetailsFieldName, new AtomicReference<>(c));
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (clusterInfoMap.isEmpty()) {
                assert running == 0 && partial == 0 && failed == 0
                    : "Non cross-cluster should have counter for running, partial and failed equal to 0";
                return new Clusters(total, successful, skipped);
            } else {
                return new Clusters(clusterInfoMap);
            }
        }

        /**
         * @return how many total clusters the search was requested to be executed on
         */
        public int getTotal() {
            return total;
        }

        /**
         * @param status the state you want to query
         * @return how many clusters are currently in a specific state
         */
        public int getClusterStateCount(Cluster.Status status) {
            if (clusterInfo.isEmpty()) {
                return switch (status) {
                    case SUCCESSFUL -> successful;
                    case SKIPPED -> skipped;
                    default -> 0;
                };
            } else {
                return determineCountFromClusterInfo(cluster -> cluster.getStatus() == status);
            }
        }

        /**
         * When Clusters is using the clusterInfo map (and Cluster objects are being updated in various
         * ActionListener threads), this method will count how many clusters match the passed in predicate.
         *
         * @param predicate to evaluate
         * @return count of clusters matching the predicate
         */
        private int determineCountFromClusterInfo(Predicate<Cluster> predicate) {
            return (int) clusterInfo.values().stream().filter(c -> predicate.test(c.get())).count();
        }

        /**
         * @return whether this search was a cross cluster search done with ccsMinimizeRoundtrips=true
         */
        public Boolean isCcsMinimizeRoundtrips() {
            return ccsMinimizeRoundtrips;
        }

        /**
         * @param clusterAlias The cluster alias as specified in the cluster collection
         * @return Cluster object associated with teh clusterAlias or null if not present
         */
        public AtomicReference<Cluster> getCluster(String clusterAlias) {
            return clusterInfo.get(clusterAlias);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Clusters clusters = (Clusters) o;
            return total == clusters.total
                && getClusterStateCount(Cluster.Status.SUCCESSFUL) == clusters.getClusterStateCount(Cluster.Status.SUCCESSFUL)
                && getClusterStateCount(Cluster.Status.SKIPPED) == clusters.getClusterStateCount(Cluster.Status.SKIPPED)
                && getClusterStateCount(Cluster.Status.RUNNING) == clusters.getClusterStateCount(Cluster.Status.RUNNING)
                && getClusterStateCount(Cluster.Status.PARTIAL) == clusters.getClusterStateCount(Cluster.Status.PARTIAL)
                && getClusterStateCount(Cluster.Status.FAILED) == clusters.getClusterStateCount(Cluster.Status.FAILED);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                total,
                getClusterStateCount(Cluster.Status.SUCCESSFUL),
                getClusterStateCount(Cluster.Status.SKIPPED),
                getClusterStateCount(Cluster.Status.RUNNING),
                getClusterStateCount(Cluster.Status.PARTIAL),
                getClusterStateCount(Cluster.Status.FAILED)
            );
        }

        @Override
        public String toString() {
            return "Clusters{total="
                + total
                + ", successful="
                + getClusterStateCount(Cluster.Status.SUCCESSFUL)
                + ", skipped="
                + getClusterStateCount(Cluster.Status.SKIPPED)
                + ", running="
                + getClusterStateCount(Cluster.Status.RUNNING)
                + ", partial="
                + getClusterStateCount(Cluster.Status.PARTIAL)
                + ", failed="
                + getClusterStateCount(Cluster.Status.FAILED)
                + '}';
        }

        /**
         * @return true if any underlying Cluster objects have PARTIAL, SKIPPED, FAILED or RUNNING status.
         *              or any Cluster is marked as timedOut.
         */
        public boolean hasPartialResults() {
            for (AtomicReference<Cluster> clusterRef : clusterInfo.values()) {
                Cluster cluster = clusterRef.get();
                switch (cluster.getStatus()) {
                    case PARTIAL, SKIPPED, FAILED, RUNNING -> {
                        return true;
                    }
                }
                if (cluster.isTimedOut()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return true if this Clusters object was initialized with underlying Cluster objects
         * for tracking search Cluster details.
         */
        public boolean hasClusterObjects() {
            return clusterInfo.keySet().size() > 0;
        }

        /**
         * @return true if this Clusters object has been initialized with remote Cluster objects
         *              This will be false for local-cluster (non-CCS) only searches.
         */
        public boolean hasRemoteClusters() {
            return total > 1 || clusterInfo.keySet().stream().anyMatch(alias -> alias != RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
        }
    }

    /**
     * Represents the search metadata about a particular cluster involved in a cross-cluster search.
     * The Cluster object can represent either the local cluster or a remote cluster.
     * For the local cluster, clusterAlias should be specified as RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY.
     * Its XContent is put into the "details" section the "_clusters" entry in the SearchResponse.
     * This is an immutable class, so updates made during the search progress (especially important for async
     * CCS searches) must be done by replacing the Cluster object with a new one.
     * See the Clusters clusterInfo Map for details.
     */
    public static class Cluster implements ToXContentFragment, Writeable {
        static final ParseField INDICES_FIELD = new ParseField("indices");
        static final ParseField STATUS_FIELD = new ParseField("status");

        private static final boolean SKIP_UNAVAILABLE_DEFAULT = false;

        private final String clusterAlias;
        private final String indexExpression; // original index expression from the user for this cluster
        private final boolean skipUnavailable;
        private final Status status;
        private final Integer totalShards;
        private final Integer successfulShards;
        private final Integer skippedShards;
        private final Integer failedShards;
        private final List<ShardSearchFailure> failures;
        private final TimeValue took;  // search latency in millis for this cluster sub-search
        private final boolean timedOut;

        /**
         * Marks the status of a Cluster search involved in a Cross-Cluster search.
         */
        public enum Status {
            RUNNING,     // still running
            SUCCESSFUL,  // all shards completed search
            PARTIAL,     // only some shards completed the search, partial results from cluster
            SKIPPED,     // entire cluster was skipped
            FAILED;      // search was failed due to errors on this cluster

            @Override
            public String toString() {
                return this.name().toLowerCase(Locale.ROOT);
            }
        }

        /**
         * Create a Cluster object representing the initial RUNNING state of a Cluster.
         *
         * @param clusterAlias clusterAlias as defined in the remote cluster settings or RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY
         *                     for the local cluster
         * @param indexExpression the original (not resolved/concrete) indices expression provided for this cluster.
         * @param skipUnavailable whether this Cluster is marked as skip_unavailable in remote cluster settings
         */
        public Cluster(String clusterAlias, String indexExpression, boolean skipUnavailable) {
            this(clusterAlias, indexExpression, skipUnavailable, Status.RUNNING, null, null, null, null, null, null, false);
        }

        /**
         * Create a Cluster with a new Status and one or more ShardSearchFailures. This constructor
         * should only be used for fatal failures where shard counters (total, successful, skipped, failed)
         * are not known (unset).
         * @param clusterAlias clusterAlias as defined in the remote cluster settings or RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY
         *                     for the local cluster
         * @param indexExpression the original (not resolved/concrete) indices expression provided for this cluster.
         * @param skipUnavailable whether cluster is marked as skip_unavailable in remote cluster settings
         * @param status current status of the search on this Cluster
         * @param failures list of failures that occurred during the search on this Cluster
         */
        public Cluster(
            String clusterAlias,
            String indexExpression,
            boolean skipUnavailable,
            Status status,
            List<ShardSearchFailure> failures
        ) {
            this(clusterAlias, indexExpression, skipUnavailable, status, null, null, null, null, failures, null, false);
        }

        public Cluster(
            String clusterAlias,
            String indexExpression,
            boolean skipUnavailable,
            Status status,
            Integer totalShards,
            Integer successfulShards,
            Integer skippedShards,
            Integer failedShards,
            List<ShardSearchFailure> failures,
            TimeValue took,
            boolean timedOut
        ) {
            assert clusterAlias != null : "clusterAlias cannot be null";
            assert indexExpression != null : "indexExpression of Cluster cannot be null";
            assert status != null : "status of Cluster cannot be null";
            this.clusterAlias = clusterAlias;
            this.indexExpression = indexExpression;
            this.skipUnavailable = skipUnavailable;
            this.status = status;
            this.totalShards = totalShards;
            this.successfulShards = successfulShards;
            this.skippedShards = skippedShards;
            this.failedShards = failedShards;
            this.failures = failures == null ? Collections.emptyList() : Collections.unmodifiableList(failures);
            this.took = took;
            this.timedOut = timedOut;
        }

        public Cluster(StreamInput in) throws IOException {
            this.clusterAlias = in.readString();
            this.indexExpression = in.readString();
            this.status = Status.valueOf(in.readString().toUpperCase(Locale.ROOT));
            this.totalShards = in.readOptionalInt();
            this.successfulShards = in.readOptionalInt();
            this.skippedShards = in.readOptionalInt();
            this.failedShards = in.readOptionalInt();
            Long took = in.readOptionalLong();
            if (took == null) {
                this.took = null;
            } else {
                this.took = new TimeValue(took);
            }
            this.timedOut = in.readBoolean();
            this.failures = Collections.unmodifiableList(in.readCollectionAsList(ShardSearchFailure::readShardSearchFailure));
            if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_500_066)) {
                this.skipUnavailable = in.readBoolean();
            } else {
                this.skipUnavailable = SKIP_UNAVAILABLE_DEFAULT;
            }
        }

        /**
         * Since the Cluster object is immutable, use this Builder class to create
         * a new Cluster object using the "copyFrom" Cluster passed in and set only
         * changed values.
         *
         * Since the clusterAlias, indexExpression and skipUnavailable fields are
         * never changed once set, this Builder provides no setter method for them.
         * All other fields can be set and override the value in the "copyFrom" Cluster.
         */
        public static class Builder {
            private Status status;
            private Integer totalShards;
            private Integer successfulShards;
            private Integer skippedShards;
            private Integer failedShards;
            private List<ShardSearchFailure> failures;
            private TimeValue took;
            private Boolean timedOut;
            private Cluster original;

            public Builder(Cluster copyFrom) {
                this.original = copyFrom;
            }

            /**
             * @return new Cluster object using the new values passed in via setters
             *         or the values in the "copyFrom" Cluster object set in the
             *         Builder constructor.
             */
            public Cluster build() {
                return new Cluster(
                    original.getClusterAlias(),
                    original.getIndexExpression(),
                    original.isSkipUnavailable(),
                    status != null ? status : original.getStatus(),
                    totalShards != null ? totalShards : original.getTotalShards(),
                    successfulShards != null ? successfulShards : original.getSuccessfulShards(),
                    skippedShards != null ? skippedShards : original.getSkippedShards(),
                    failedShards != null ? failedShards : original.getFailedShards(),
                    failures != null ? failures : original.getFailures(),
                    took != null ? took : original.getTook(),
                    timedOut != null ? timedOut : original.isTimedOut()
                );
            }

            public Builder setStatus(Status status) {
                this.status = status;
                return this;
            }

            public Builder setTotalShards(int totalShards) {
                this.totalShards = totalShards;
                return this;
            }

            public Builder setSuccessfulShards(int successfulShards) {
                this.successfulShards = successfulShards;
                return this;
            }

            public Builder setSkippedShards(int skippedShards) {
                this.skippedShards = skippedShards;
                return this;
            }

            public Builder setFailedShards(int failedShards) {
                this.failedShards = failedShards;
                return this;
            }

            public Builder setFailures(List<ShardSearchFailure> failures) {
                this.failures = failures;
                return this;
            }

            public Builder setTook(TimeValue took) {
                this.took = took;
                return this;
            }

            public Builder setTimedOut(boolean timedOut) {
                this.timedOut = timedOut;
                return this;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(clusterAlias);
            out.writeString(indexExpression);
            out.writeString(status.toString());
            out.writeOptionalInt(totalShards);
            out.writeOptionalInt(successfulShards);
            out.writeOptionalInt(skippedShards);
            out.writeOptionalInt(failedShards);
            out.writeOptionalLong(took == null ? null : took.millis());
            out.writeBoolean(timedOut);
            out.writeCollection(failures);
            if (out.getTransportVersion().onOrAfter(TransportVersions.SEARCH_RESP_SKIP_UNAVAILABLE_ADDED)) {
                out.writeBoolean(skipUnavailable);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            String name = clusterAlias;
            if (clusterAlias.equals("")) {
                name = "(local)";
            }
            builder.startObject(name);
            {
                builder.field(STATUS_FIELD.getPreferredName(), getStatus().toString());
                builder.field(INDICES_FIELD.getPreferredName(), indexExpression);
                if (took != null) {
                    builder.field(TOOK.getPreferredName(), took.millis());
                }
                builder.field(TIMED_OUT.getPreferredName(), timedOut);
                if (totalShards != null) {
                    builder.startObject(RestActions._SHARDS_FIELD.getPreferredName());
                    builder.field(RestActions.TOTAL_FIELD.getPreferredName(), totalShards);
                    if (successfulShards != null) {
                        builder.field(RestActions.SUCCESSFUL_FIELD.getPreferredName(), successfulShards);
                    }
                    if (skippedShards != null) {
                        builder.field(RestActions.SKIPPED_FIELD.getPreferredName(), skippedShards);
                    }
                    if (failedShards != null) {
                        builder.field(RestActions.FAILED_FIELD.getPreferredName(), failedShards);
                    }
                    builder.endObject();
                }
                if (failures != null && failures.size() > 0) {
                    builder.startArray(RestActions.FAILURES_FIELD.getPreferredName());
                    for (ShardSearchFailure failure : failures) {
                        failure.toXContent(builder, params);
                    }
                    builder.endArray();
                }
            }
            builder.endObject();
            return builder;
        }

        public static Cluster fromXContent(String clusterAlias, XContentParser parser) throws IOException {
            XContentParser.Token token = parser.currentToken();
            ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser);

            String clusterName = clusterAlias;
            if (clusterAlias.equals("(local)")) {
                clusterName = "";
            }
            String indexExpression = null;
            String status = "running";
            boolean timedOut = false;
            long took = -1L;
            // these are all from the _shards section
            int totalShards = -1;
            int successfulShards = -1;
            int skippedShards = -1;
            int failedShards = -1;
            List<ShardSearchFailure> failures = new ArrayList<>();

            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if (Cluster.INDICES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        indexExpression = parser.text();
                    } else if (Cluster.STATUS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        status = parser.text();
                    } else if (TIMED_OUT.match(currentFieldName, parser.getDeprecationHandler())) {
                        timedOut = parser.booleanValue();
                    } else if (TOOK.match(currentFieldName, parser.getDeprecationHandler())) {
                        took = parser.longValue();
                    } else {
                        parser.skipChildren();
                    }
                } else if (RestActions._SHARDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    while ((token = parser.nextToken()) != Token.END_OBJECT) {
                        if (token == Token.FIELD_NAME) {
                            currentFieldName = parser.currentName();
                        } else if (token.isValue()) {
                            if (RestActions.FAILED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                failedShards = parser.intValue();
                            } else if (RestActions.SUCCESSFUL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                successfulShards = parser.intValue();
                            } else if (RestActions.TOTAL_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                totalShards = parser.intValue();
                            } else if (RestActions.SKIPPED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                skippedShards = parser.intValue();
                            } else {
                                parser.skipChildren();
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else if (token == Token.START_ARRAY) {
                    if (RestActions.FAILURES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                        while ((token = parser.nextToken()) != Token.END_ARRAY) {
                            failures.add(ShardSearchFailure.fromXContent(parser));
                        }
                    } else {
                        parser.skipChildren();
                    }
                } else {
                    parser.skipChildren();
                }
            }

            Integer totalShardsFinal = totalShards == -1 ? null : totalShards;
            Integer successfulShardsFinal = successfulShards == -1 ? null : successfulShards;
            Integer skippedShardsFinal = skippedShards == -1 ? null : skippedShards;
            Integer failedShardsFinal = failedShards == -1 ? null : failedShards;
            TimeValue tookTimeValue = took == -1L ? null : new TimeValue(took);
            boolean skipUnavailable = SKIP_UNAVAILABLE_DEFAULT;  // skipUnavailable is not exposed to XContent, so just use default

            return new Cluster(
                clusterName,
                indexExpression,
                skipUnavailable,
                SearchResponse.Cluster.Status.valueOf(status.toUpperCase(Locale.ROOT)),
                totalShardsFinal,
                successfulShardsFinal,
                skippedShardsFinal,
                failedShardsFinal,
                failures,
                tookTimeValue,
                timedOut
            );
        }

        public String getClusterAlias() {
            return clusterAlias;
        }

        public String getIndexExpression() {
            return indexExpression;
        }

        public boolean isSkipUnavailable() {
            return skipUnavailable;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public List<ShardSearchFailure> getFailures() {
            return failures;
        }

        public TimeValue getTook() {
            return took;
        }

        public Integer getTotalShards() {
            return totalShards;
        }

        public Integer getSuccessfulShards() {
            return successfulShards;
        }

        public Integer getSkippedShards() {
            return skippedShards;
        }

        public Integer getFailedShards() {
            return failedShards;
        }

        @Override
        public String toString() {
            return "Cluster{"
                + "alias='"
                + clusterAlias
                + '\''
                + ", status="
                + status
                + ", totalShards="
                + totalShards
                + ", successfulShards="
                + successfulShards
                + ", skippedShards="
                + skippedShards
                + ", failedShards="
                + failedShards
                + ", failures(sz)="
                + failures.size()
                + ", took="
                + took
                + ", timedOut="
                + timedOut
                + ", indexExpression='"
                + indexExpression
                + '\''
                + ", skipUnavailable="
                + skipUnavailable
                + '}';
        }
    }

    // public for tests
    public static SearchResponse empty(Supplier<Long> tookInMillisSupplier, Clusters clusters) {
        SearchHits searchHits = new SearchHits(new SearchHit[0], new TotalHits(0L, TotalHits.Relation.EQUAL_TO), Float.NaN);
        InternalSearchResponse internalSearchResponse = new InternalSearchResponse(
            searchHits,
            InternalAggregations.EMPTY,
            null,
            null,
            false,
            null,
            0
        );
        return new SearchResponse(
            internalSearchResponse,
            null,
            0,
            0,
            0,
            tookInMillisSupplier.get(),
            ShardSearchFailure.EMPTY_ARRAY,
            clusters,
            null
        );
    }
}
