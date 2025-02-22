/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.search;

import org.apache.lucene.index.CorruptIndexException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchResponseSections;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ChunkedToXContent;
import org.elasticsearch.common.xcontent.XContentElasticsearchExtension;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.script.ScriptException;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.search.action.AsyncSearchResponse;
import org.junit.Before;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.elasticsearch.xpack.core.async.GetAsyncResultRequestTests.randomSearchId;

public class AsyncSearchResponseTests extends ESTestCase {
    private final SearchResponse searchResponse = randomSearchResponse(randomBoolean());
    private NamedWriteableRegistry namedWriteableRegistry;

    @Before
    public void registerNamedObjects() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, emptyList());

        List<NamedWriteableRegistry.Entry> namedWriteables = searchModule.getNamedWriteables();
        namedWriteableRegistry = new NamedWriteableRegistry(namedWriteables);
    }

    protected Writeable.Reader<AsyncSearchResponse> instanceReader() {
        return AsyncSearchResponse::new;
    }

    protected AsyncSearchResponse createTestInstance() {
        return randomAsyncSearchResponse(randomSearchId(), searchResponse);
    }

    protected void assertEqualInstances(AsyncSearchResponse expectedInstance, AsyncSearchResponse newInstance) {
        assertNotSame(newInstance, expectedInstance);
        assertEqualResponses(expectedInstance, newInstance);
    }

    public final void testSerialization() throws IOException {
        for (int runs = 0; runs < 10; runs++) {
            AsyncSearchResponse testInstance = createTestInstance();
            assertSerialization(testInstance);
        }
    }

    protected final AsyncSearchResponse assertSerialization(AsyncSearchResponse testInstance) throws IOException {
        return assertSerialization(testInstance, TransportVersion.current());
    }

    protected final AsyncSearchResponse assertSerialization(AsyncSearchResponse testInstance, TransportVersion version) throws IOException {
        AsyncSearchResponse deserializedInstance = copyInstance(testInstance, version);
        assertEqualInstances(testInstance, deserializedInstance);
        return deserializedInstance;
    }

    protected final AsyncSearchResponse copyInstance(AsyncSearchResponse instance) throws IOException {
        return copyInstance(instance, TransportVersion.current());
    }

    protected AsyncSearchResponse copyInstance(AsyncSearchResponse instance, TransportVersion version) throws IOException {
        return copyWriteable(instance, namedWriteableRegistry, instanceReader(), version);
    }

    static AsyncSearchResponse randomAsyncSearchResponse(String searchId, SearchResponse searchResponse) {
        int rand = randomIntBetween(0, 2);
        return switch (rand) {
            case 0 -> new AsyncSearchResponse(searchId, randomBoolean(), randomBoolean(), randomNonNegativeLong(), randomNonNegativeLong());
            case 1 -> new AsyncSearchResponse(
                searchId,
                searchResponse,
                null,
                randomBoolean(),
                randomBoolean(),
                randomNonNegativeLong(),
                randomNonNegativeLong()
            );
            case 2 -> new AsyncSearchResponse(
                searchId,
                searchResponse,
                new ScriptException("messageData", new Exception("causeData"), Arrays.asList("stack1", "stack2"), "sourceData", "langData"),
                randomBoolean(),
                randomBoolean(),
                randomNonNegativeLong(),
                randomNonNegativeLong()
            );
            default -> throw new AssertionError();
        };
    }

    static SearchResponse randomSearchResponse(boolean ccs) {
        long tookInMillis = randomNonNegativeLong();
        int totalShards = randomIntBetween(1, Integer.MAX_VALUE);
        int successfulShards = randomIntBetween(0, totalShards);
        int skippedShards = randomIntBetween(0, successfulShards);
        InternalSearchResponse internalSearchResponse = InternalSearchResponse.EMPTY_WITH_TOTAL_HITS;
        SearchResponse.Clusters clusters;
        if (ccs) {
            clusters = createCCSClusterObjects(20, 19, true, 10, 1, 2);
        } else {
            clusters = SearchResponse.Clusters.EMPTY;
        }
        return new SearchResponse(
            internalSearchResponse,
            null,
            totalShards,
            successfulShards,
            skippedShards,
            tookInMillis,
            ShardSearchFailure.EMPTY_ARRAY,
            clusters
        );
    }

    static void assertEqualResponses(AsyncSearchResponse expected, AsyncSearchResponse actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.getFailure() == null, actual.getFailure() == null);
        assertEquals(expected.isRunning(), actual.isRunning());
        assertEquals(expected.isPartial(), actual.isPartial());
        assertEquals(expected.getStartTime(), actual.getStartTime());
        assertEquals(expected.getExpirationTime(), actual.getExpirationTime());
    }

    public void testToXContentWithoutSearchResponse() throws IOException {
        Date date = new Date();
        AsyncSearchResponse asyncSearchResponse = new AsyncSearchResponse("id", true, true, date.getTime(), date.getTime());

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse).toXContent(builder, ToXContent.EMPTY_PARAMS);
            assertEquals(Strings.format("""
                {
                  "id" : "id",
                  "is_partial" : true,
                  "is_running" : true,
                  "start_time_in_millis" : %s,
                  "expiration_time_in_millis" : %s
                }""", date.getTime(), date.getTime()), Strings.toString(builder));
        }

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            builder.humanReadable(true);
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse)
                .toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("human", "true")));
            assertEquals(
                Strings.format(
                    """
                        {
                          "id" : "id",
                          "is_partial" : true,
                          "is_running" : true,
                          "start_time" : "%s",
                          "start_time_in_millis" : %s,
                          "expiration_time" : "%s",
                          "expiration_time_in_millis" : %s
                        }""",
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(date.toInstant()),
                    date.getTime(),
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(date.toInstant()),
                    date.getTime()
                ),
                Strings.toString(builder)
            );
        }
    }

    // completion_time should be present since search has completed
    public void testToXContentWithSearchResponseAfterCompletion() throws IOException {
        boolean isRunning = false;
        long startTimeMillis = 1689352924517L;
        long expirationTimeMillis = 1689784924517L;
        long took = 22968L;
        long expectedCompletionTime = startTimeMillis + took;

        SearchHits hits = SearchHits.EMPTY_WITHOUT_TOTAL_HITS;
        SearchResponseSections sections = new SearchResponseSections(hits, null, null, false, null, null, 2);
        SearchResponse searchResponse = new SearchResponse(
            sections,
            null,
            10,
            9,
            1,
            took,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );

        AsyncSearchResponse asyncSearchResponse = new AsyncSearchResponse(
            "id",
            searchResponse,
            null,
            false,
            isRunning,
            startTimeMillis,
            expirationTimeMillis
        );

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse).toXContent(builder, ToXContent.EMPTY_PARAMS);
            assertEquals(Strings.format("""
                {
                  "id" : "id",
                  "is_partial" : false,
                  "is_running" : false,
                  "start_time_in_millis" : %s,
                  "expiration_time_in_millis" : %s,
                  "completion_time_in_millis" : %s,
                  "response" : {
                    "took" : %s,
                    "timed_out" : false,
                    "num_reduce_phases" : 2,
                    "_shards" : {
                      "total" : 10,
                      "successful" : 9,
                      "skipped" : 1,
                      "failed" : 0
                    },
                    "hits" : {
                      "max_score" : 0.0,
                      "hits" : [ ]
                    }
                  }
                }""", startTimeMillis, expirationTimeMillis, expectedCompletionTime, took), Strings.toString(builder));
        }

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            builder.humanReadable(true);
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse)
                .toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("human", "true")));
            assertEquals(
                Strings.format(
                    """
                        {
                          "id" : "id",
                          "is_partial" : false,
                          "is_running" : false,
                          "start_time" : "%s",
                          "start_time_in_millis" : %s,
                          "expiration_time" : "%s",
                          "expiration_time_in_millis" : %s,
                          "completion_time" : "%s",
                          "completion_time_in_millis" : %s,
                          "response" : {
                            "took" : %s,
                            "timed_out" : false,
                            "num_reduce_phases" : 2,
                            "_shards" : {
                              "total" : 10,
                              "successful" : 9,
                              "skipped" : 1,
                              "failed" : 0
                            },
                            "hits" : {
                              "max_score" : 0.0,
                              "hits" : [ ]
                            }
                          }
                        }""",
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(startTimeMillis)),
                    startTimeMillis,
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(expirationTimeMillis)),
                    expirationTimeMillis,
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(expectedCompletionTime)),
                    expectedCompletionTime,
                    took
                ),
                Strings.toString(builder)
            );
        }
    }

    public void testToXContentWithCCSSearchResponseWhileRunning() throws IOException {
        boolean isRunning = true;
        long startTimeMillis = 1689352924517L;
        long expirationTimeMillis = 1689784924517L;
        long took = 22968L;

        SearchHits hits = SearchHits.EMPTY_WITHOUT_TOTAL_HITS;
        SearchResponseSections sections = new SearchResponseSections(hits, null, null, false, null, null, 2);

        SearchResponse.Clusters clusters = createCCSClusterObjects(3, 3, true);

        SearchResponse searchResponse = new SearchResponse(sections, null, 10, 9, 1, took, ShardSearchFailure.EMPTY_ARRAY, clusters);

        AsyncSearchResponse asyncSearchResponse = new AsyncSearchResponse(
            "id",
            searchResponse,
            null,
            true,
            isRunning,
            startTimeMillis,
            expirationTimeMillis
        );

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse).toXContent(builder, ToXContent.EMPTY_PARAMS);
            assertEquals(Strings.format("""
                {
                  "id" : "id",
                  "is_partial" : true,
                  "is_running" : true,
                  "start_time_in_millis" : %s,
                  "expiration_time_in_millis" : %s,
                  "response" : {
                    "took" : %s,
                    "timed_out" : false,
                    "num_reduce_phases" : 2,
                    "_shards" : {
                      "total" : 10,
                      "successful" : 9,
                      "skipped" : 1,
                      "failed" : 0
                    },
                    "_clusters" : {
                      "total" : 3,
                      "successful" : 0,
                      "skipped" : 0,
                      "running" : 3,
                      "partial" : 0,
                      "failed" : 0,
                      "details" : {
                        "cluster_1" : {
                          "status" : "running",
                          "indices" : "foo,bar*",
                          "timed_out" : false
                        },
                        "cluster_2" : {
                          "status" : "running",
                          "indices" : "foo,bar*",
                          "timed_out" : false
                        },
                        "cluster_0" : {
                          "status" : "running",
                          "indices" : "foo,bar*",
                          "timed_out" : false
                        }
                      }
                    },
                    "hits" : {
                      "max_score" : 0.0,
                      "hits" : [ ]
                    }
                  }
                }""", startTimeMillis, expirationTimeMillis, took), Strings.toString(builder));
        }

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            builder.humanReadable(true);
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse)
                .toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("human", "true")));
            assertEquals(
                Strings.format(
                    """
                        {
                          "id" : "id",
                          "is_partial" : true,
                          "is_running" : true,
                          "start_time" : "%s",
                          "start_time_in_millis" : %s,
                          "expiration_time" : "%s",
                          "expiration_time_in_millis" : %s,
                          "response" : {
                            "took" : %s,
                            "timed_out" : false,
                            "num_reduce_phases" : 2,
                            "_shards" : {
                              "total" : 10,
                              "successful" : 9,
                              "skipped" : 1,
                              "failed" : 0
                            },
                            "_clusters" : {
                              "total" : 3,
                              "successful" : 0,
                              "skipped" : 0,
                              "running" : 3,
                              "partial" : 0,
                              "failed" : 0,
                              "details" : {
                                "cluster_1" : {
                                  "status" : "running",
                                  "indices" : "foo,bar*",
                                  "timed_out" : false
                                },
                                "cluster_2" : {
                                  "status" : "running",
                                  "indices" : "foo,bar*",
                                  "timed_out" : false
                                },
                                "cluster_0" : {
                                  "status" : "running",
                                  "indices" : "foo,bar*",
                                  "timed_out" : false
                                }
                              }
                            },
                            "hits" : {
                              "max_score" : 0.0,
                              "hits" : [ ]
                            }
                          }
                        }""",
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(startTimeMillis)),
                    startTimeMillis,
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(expirationTimeMillis)),
                    expirationTimeMillis,
                    took
                ),
                Strings.toString(builder)
            );
        }
    }

    // completion_time should be present since search has completed
    public void testToXContentWithCCSSearchResponseAfterCompletion() throws IOException {
        boolean isRunning = false;
        long startTimeMillis = 1689352924517L;
        long expirationTimeMillis = 1689784924517L;
        long took = 22968L;
        long expectedCompletionTime = startTimeMillis + took;

        SearchHits hits = SearchHits.EMPTY_WITHOUT_TOTAL_HITS;
        SearchResponseSections sections = new SearchResponseSections(hits, null, null, true, null, null, 2);
        SearchResponse.Clusters clusters = createCCSClusterObjects(4, 3, true);

        AtomicReference<SearchResponse.Cluster> clusterRef = clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
        SearchResponse.Cluster localCluster = clusterRef.get();
        SearchResponse.Cluster updated = new SearchResponse.Cluster(
            localCluster.getClusterAlias(),
            localCluster.getIndexExpression(),
            false,
            SearchResponse.Cluster.Status.SUCCESSFUL,
            10,
            10,
            3,
            0,
            Collections.emptyList(),
            new TimeValue(11111),
            false
        );
        boolean swapped = clusterRef.compareAndSet(localCluster, updated);
        assertTrue("CAS swap failed for cluster " + updated, swapped);

        clusterRef = clusters.getCluster("cluster_0");
        SearchResponse.Cluster cluster0 = clusterRef.get();
        updated = new SearchResponse.Cluster(
            cluster0.getClusterAlias(),
            cluster0.getIndexExpression(),
            false,
            SearchResponse.Cluster.Status.SUCCESSFUL,
            8,
            8,
            1,
            0,
            Collections.emptyList(),
            new TimeValue(7777),
            false
        );
        swapped = clusterRef.compareAndSet(cluster0, updated);
        assertTrue("CAS swap failed for cluster " + updated, swapped);

        clusterRef = clusters.getCluster("cluster_1");
        SearchResponse.Cluster cluster1 = clusterRef.get();
        ShardSearchFailure failure1 = new ShardSearchFailure(
            new NullPointerException("NPE details"),
            new SearchShardTarget("nodeId0", new ShardId("foo", UUID.randomUUID().toString(), 0), "cluster_1")
        );
        ShardSearchFailure failure2 = new ShardSearchFailure(
            new CorruptIndexException("abc", "123"),
            new SearchShardTarget("nodeId0", new ShardId("bar1", UUID.randomUUID().toString(), 0), "cluster_1")
        );
        updated = new SearchResponse.Cluster(
            cluster1.getClusterAlias(),
            cluster1.getIndexExpression(),
            false,
            SearchResponse.Cluster.Status.SKIPPED,
            2,
            0,
            0,
            2,
            List.of(failure1, failure2),
            null,
            false
        );
        swapped = clusterRef.compareAndSet(cluster1, updated);
        assertTrue("CAS swap failed for cluster " + updated, swapped);

        clusterRef = clusters.getCluster("cluster_2");
        SearchResponse.Cluster cluster2 = clusterRef.get();
        updated = new SearchResponse.Cluster(
            cluster2.getClusterAlias(),
            cluster2.getIndexExpression(),
            false,
            SearchResponse.Cluster.Status.PARTIAL,
            8,
            8,
            0,
            0,
            Collections.emptyList(),
            new TimeValue(17322),
            true
        );
        swapped = clusterRef.compareAndSet(cluster2, updated);
        assertTrue("CAS swap failed for cluster " + updated, swapped);

        SearchResponse searchResponse = new SearchResponse(sections, null, 10, 9, 1, took, new ShardSearchFailure[0], clusters);

        AsyncSearchResponse asyncSearchResponse = new AsyncSearchResponse(
            "id",
            searchResponse,
            null,
            false,
            isRunning,
            startTimeMillis,
            expirationTimeMillis
        );

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse).toXContent(builder, ToXContent.EMPTY_PARAMS);
            assertEquals(Strings.format("""
                {
                  "id" : "id",
                  "is_partial" : false,
                  "is_running" : false,
                  "start_time_in_millis" : %s,
                  "expiration_time_in_millis" : %s,
                  "completion_time_in_millis" : %s,
                  "response" : {
                    "took" : %s,
                    "timed_out" : true,
                    "num_reduce_phases" : 2,
                    "_shards" : {
                      "total" : 10,
                      "successful" : 9,
                      "skipped" : 1,
                      "failed" : 0
                    },
                    "_clusters" : {
                      "total" : 4,
                      "successful" : 2,
                      "skipped" : 1,
                      "running" : 0,
                      "partial" : 1,
                      "failed" : 0,
                      "details" : {
                        "(local)" : {
                          "status" : "successful",
                          "indices" : "foo,bar*",
                          "took" : 11111,
                          "timed_out" : false,
                          "_shards" : {
                            "total" : 10,
                            "successful" : 10,
                            "skipped" : 3,
                            "failed" : 0
                          }
                        },
                        "cluster_1" : {
                          "status" : "skipped",
                          "indices" : "foo,bar*",
                          "timed_out" : false,
                          "_shards" : {
                            "total" : 2,
                            "successful" : 0,
                            "skipped" : 0,
                            "failed" : 2
                          },
                          "failures" : [
                            {
                              "shard" : 0,
                              "index" : "cluster_1:foo",
                              "node" : "nodeId0",
                              "reason" : {
                                "type" : "null_pointer_exception",
                                "reason" : "NPE details"
                              }
                            },
                            {
                              "shard" : 0,
                              "index" : "cluster_1:bar1",
                              "node" : "nodeId0",
                              "reason" : {
                                "type" : "corrupt_index_exception",
                                "reason" : "abc (resource=123)"
                              }
                            }
                          ]
                        },
                        "cluster_2" : {
                          "status" : "partial",
                          "indices" : "foo,bar*",
                          "took" : 17322,
                          "timed_out" : true,
                          "_shards" : {
                            "total" : 8,
                            "successful" : 8,
                            "skipped" : 0,
                            "failed" : 0
                          }
                        },
                        "cluster_0" : {
                          "status" : "successful",
                          "indices" : "foo,bar*",
                          "took" : 7777,
                          "timed_out" : false,
                          "_shards" : {
                            "total" : 8,
                            "successful" : 8,
                            "skipped" : 1,
                            "failed" : 0
                          }
                        }
                      }
                    },
                    "hits" : {
                      "max_score" : 0.0,
                      "hits" : [ ]
                    }
                  }
                }""", startTimeMillis, expirationTimeMillis, expectedCompletionTime, took), Strings.toString(builder));
        }
    }

    // completion_time should NOT be present since search is still running
    public void testToXContentWithSearchResponseWhileRunning() throws IOException {
        boolean isRunning = true;
        long startTimeMillis = 1689352924517L;
        long expirationTimeMillis = 1689784924517L;
        long took = 22968L;

        SearchHits hits = SearchHits.EMPTY_WITHOUT_TOTAL_HITS;
        SearchResponseSections sections = new SearchResponseSections(hits, null, null, false, null, null, 2);
        SearchResponse searchResponse = new SearchResponse(
            sections,
            null,
            10,
            9,
            1,
            took,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );

        AsyncSearchResponse asyncSearchResponse = new AsyncSearchResponse(
            "id",
            searchResponse,
            null,
            true,
            isRunning,
            startTimeMillis,
            expirationTimeMillis
        );

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse).toXContent(builder, ToXContent.EMPTY_PARAMS);
            assertEquals(Strings.format("""
                {
                  "id" : "id",
                  "is_partial" : true,
                  "is_running" : true,
                  "start_time_in_millis" : %s,
                  "expiration_time_in_millis" : %s,
                  "response" : {
                    "took" : %s,
                    "timed_out" : false,
                    "num_reduce_phases" : 2,
                    "_shards" : {
                      "total" : 10,
                      "successful" : 9,
                      "skipped" : 1,
                      "failed" : 0
                    },
                    "hits" : {
                      "max_score" : 0.0,
                      "hits" : [ ]
                    }
                  }
                }""", startTimeMillis, expirationTimeMillis, took), Strings.toString(builder));
        }

        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.prettyPrint();
            builder.humanReadable(true);
            ChunkedToXContent.wrapAsToXContent(asyncSearchResponse)
                .toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("human", "true")));
            assertEquals(
                Strings.format(
                    """
                        {
                          "id" : "id",
                          "is_partial" : true,
                          "is_running" : true,
                          "start_time" : "%s",
                          "start_time_in_millis" : %s,
                          "expiration_time" : "%s",
                          "expiration_time_in_millis" : %s,
                          "response" : {
                            "took" : %s,
                            "timed_out" : false,
                            "num_reduce_phases" : 2,
                            "_shards" : {
                              "total" : 10,
                              "successful" : 9,
                              "skipped" : 1,
                              "failed" : 0
                            },
                            "hits" : {
                              "max_score" : 0.0,
                              "hits" : [ ]
                            }
                          }
                        }""",
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(startTimeMillis)),
                    startTimeMillis,
                    XContentElasticsearchExtension.DEFAULT_FORMATTER.format(Instant.ofEpochMilli(expirationTimeMillis)),
                    expirationTimeMillis,
                    took
                ),
                Strings.toString(builder)
            );
        }
    }

    static SearchResponse.Clusters createCCSClusterObjects(int totalClusters, int remoteClusters, boolean ccsMinimizeRoundtrips) {
        OriginalIndices localIndices = null;
        if (totalClusters > remoteClusters) {
            localIndices = new OriginalIndices(new String[] { "foo", "bar*" }, IndicesOptions.lenientExpand());
        }
        assert remoteClusters > 0 : "CCS Cluster must have at least one remote cluster";
        Map<String, OriginalIndices> remoteClusterIndices = new HashMap<>();
        for (int i = 0; i < remoteClusters; i++) {
            remoteClusterIndices.put("cluster_" + i, new OriginalIndices(new String[] { "foo", "bar*" }, IndicesOptions.lenientExpand()));
        }

        return new SearchResponse.Clusters(localIndices, remoteClusterIndices, ccsMinimizeRoundtrips, alias -> false);
    }

    static SearchResponse.Clusters createCCSClusterObjects(
        int totalClusters,
        int remoteClusters,
        boolean ccsMinimizeRoundtrips,
        int successfulClusters,
        int skippedClusters,
        int partialClusters
    ) {
        assert successfulClusters + skippedClusters <= totalClusters : "successful + skipped > totalClusters";
        assert totalClusters == remoteClusters || totalClusters - remoteClusters == 1
            : "totalClusters and remoteClusters must be same or total = remote + 1";
        assert successfulClusters + skippedClusters + partialClusters > 0 : "successful + skipped + partial must be > 0";

        SearchResponse.Clusters clusters = createCCSClusterObjects(totalClusters, remoteClusters, ccsMinimizeRoundtrips);

        int successful = successfulClusters;
        int skipped = skippedClusters;
        int partial = partialClusters;
        if (totalClusters > remoteClusters) {
            String localAlias = RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
            AtomicReference<SearchResponse.Cluster> localRef = clusters.getCluster(localAlias);
            SearchResponse.Cluster orig = localRef.get();
            SearchResponse.Cluster updated;
            if (successful > 0) {
                updated = new SearchResponse.Cluster(
                    localAlias,
                    localRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.SUCCESSFUL,
                    5,
                    5,
                    0,
                    0,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                successful--;
            } else if (skipped > 0) {
                updated = new SearchResponse.Cluster(
                    localAlias,
                    localRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.SKIPPED,
                    5,
                    0,
                    0,
                    5,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                skipped--;
            } else {
                updated = new SearchResponse.Cluster(
                    localAlias,
                    localRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.PARTIAL,
                    5,
                    2,
                    1,
                    3,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                partial--;
            }
            boolean swapped = localRef.compareAndSet(orig, updated);
            assertTrue("CAS swap failed for cluster " + updated, swapped);
        }

        int numClusters = successful + skipped + partial;

        for (int i = 0; i < numClusters; i++) {
            String clusterAlias = "cluster_" + i;
            AtomicReference<SearchResponse.Cluster> clusterRef = clusters.getCluster(clusterAlias);
            SearchResponse.Cluster orig = clusterRef.get();
            SearchResponse.Cluster updated;
            if (successful > 0) {
                updated = new SearchResponse.Cluster(
                    clusterAlias,
                    clusterRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.SUCCESSFUL,
                    5,
                    5,
                    0,
                    0,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                successful--;
            } else if (skipped > 0) {
                updated = new SearchResponse.Cluster(
                    clusterAlias,
                    clusterRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.SKIPPED,
                    5,
                    0,
                    0,
                    5,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                skipped--;
            } else {
                updated = new SearchResponse.Cluster(
                    clusterAlias,
                    clusterRef.get().getIndexExpression(),
                    false,
                    SearchResponse.Cluster.Status.PARTIAL,
                    5,
                    2,
                    1,
                    3,
                    Collections.emptyList(),
                    new TimeValue(1000),
                    false
                );
                partial--;
            }
            boolean swapped = clusterRef.compareAndSet(orig, updated);
            assertTrue("CAS swap failed for cluster " + updated, swapped);
        }
        return clusters;
    }
}
