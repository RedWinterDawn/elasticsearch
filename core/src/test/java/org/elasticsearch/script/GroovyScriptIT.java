/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.script;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyScriptEngineService;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders.scriptFunction;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;
import static org.hamcrest.Matchers.equalTo;

/**
 * Various tests for Groovy scripting
 */
public class GroovyScriptIT extends ESIntegTestCase {

    @Test
    public void testGroovyBigDecimalTransformation() {
        client().prepareIndex("test", "doc", "1").setSource("foo", 5).setRefresh(true).get();

        // Test that something that would usually be a BigDecimal is transformed into a Double
        assertScript("def n = 1.23; assert n instanceof Double;");
        assertScript("def n = 1.23G; assert n instanceof Double;");
        assertScript("def n = BigDecimal.ONE; assert n instanceof BigDecimal;");
    }

    public void assertScript(String script) {
        SearchResponse resp = client().prepareSearch("test")
                .setSource(new BytesArray("{\"query\": {\"match_all\": {}}," +
                        "\"sort\":{\"_script\": {\"script\": \""+ script +
                        "; 1\", \"type\": \"number\", \"lang\": \"groovy\"}}}")).get();
        assertNoFailures(resp);
    }

    @Test
    public void testGroovyExceptionSerialization() throws Exception {
        List<IndexRequestBuilder> reqs = new ArrayList<>();
        for (int i = 0; i < randomIntBetween(50, 500); i++) {
            reqs.add(client().prepareIndex("test", "doc", "" + i).setSource("foo", "bar"));
        }
        indexRandom(true, false, reqs);
        try {
            client().prepareSearch("test")
                    .setQuery(
                            constantScoreQuery(scriptQuery(new Script("1 == not_found", ScriptType.INLINE, GroovyScriptEngineService.NAME,
                                    null)))).get();
            fail("should have thrown an exception");
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString()+ "should not contained NotSerializableTransportException",
                    e.toString().contains("NotSerializableTransportException"), equalTo(false));
            assertThat(e.toString()+ "should have contained GroovyScriptExecutionException",
                    e.toString().contains("GroovyScriptExecutionException"), equalTo(true));
            assertThat(e.toString()+ "should have contained not_found",
                    e.toString().contains("No such property: not_found"), equalTo(true));
        }

        try {
            client().prepareSearch("test")
                    .setQuery(constantScoreQuery(scriptQuery(new Script("assert false", ScriptType.INLINE, "groovy", null)))).get();
            fail("should have thrown an exception");
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString() + "should not contained NotSerializableTransportException",
                    e.toString().contains("NotSerializableTransportException"), equalTo(false));
            assertThat(e.toString() + "should have contained GroovyScriptExecutionException",
                    e.toString().contains("GroovyScriptExecutionException"), equalTo(true));
            assertThat(e.toString()+ "should have contained an assert error",
                    e.toString().contains("AssertionError[assert false"), equalTo(true));
        }
    }

    @Test
    public void testGroovyScriptAccess() {
        client().prepareIndex("test", "doc", "1").setSource("foo", "quick brow fox jumped over the lazy dog", "bar", 1).get();
        client().prepareIndex("test", "doc", "2").setSource("foo", "fast jumping spiders", "bar", 2).get();
        client().prepareIndex("test", "doc", "3").setSource("foo", "dog spiders that can eat a dog", "bar", 3).get();
        refresh();

        // doc[] access
        SearchResponse resp = client().prepareSearch("test").setQuery(functionScoreQuery(scriptFunction(new Script("doc['bar'].value", ScriptType.INLINE, "groovy", null)))
            .boostMode(CombineFunction.REPLACE)).get();

        assertNoFailures(resp);
        assertOrderedSearchHits(resp, "3", "2", "1");
    }
    
    public void testScoreAccess() {
        client().prepareIndex("test", "doc", "1").setSource("foo", "quick brow fox jumped over the lazy dog", "bar", 1).get();
        client().prepareIndex("test", "doc", "2").setSource("foo", "fast jumping spiders", "bar", 2).get();
        client().prepareIndex("test", "doc", "3").setSource("foo", "dog spiders that can eat a dog", "bar", 3).get();
        refresh();

        // _score can be accessed
        SearchResponse resp = client().prepareSearch("test").setQuery(functionScoreQuery(matchQuery("foo", "dog"),
                scriptFunction(new Script("_score", ScriptType.INLINE, "groovy", null)))
            .boostMode(CombineFunction.REPLACE)).get();
        assertNoFailures(resp);
        assertSearchHits(resp, "3", "1");

        // _score is comparable
        // NOTE: it is important to use 0.0 instead of 0 instead Groovy will do an integer comparison
        // and if the score if between 0 and 1 it will be considered equal to 0 due to the cast
        resp = client()
                .prepareSearch("test")
                .setQuery(
                        functionScoreQuery(matchQuery("foo", "dog"),
                                scriptFunction(new Script("_score > 0.0 ? _score : 0", ScriptType.INLINE, "groovy", null))).boostMode(
                                CombineFunction.REPLACE)).get();
        assertNoFailures(resp);
        assertSearchHits(resp, "3", "1");
    }
}
