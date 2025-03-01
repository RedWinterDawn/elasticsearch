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

package org.elasticsearch.index.query;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.support.QueryParsers;

import java.io.IOException;
import java.util.Objects;

/**
 * A Query that does fuzzy matching for a specific value.
 */
public class FuzzyQueryBuilder extends AbstractQueryBuilder<FuzzyQueryBuilder> implements MultiTermQueryBuilder<FuzzyQueryBuilder> {

    public static final String NAME = "fuzzy";

    /** Default maximum edit distance. Defaults to AUTO. */
    public static final Fuzziness DEFAULT_FUZZINESS = Fuzziness.AUTO;

    /** Default number of initial characters which will not be “fuzzified”. Defaults to 0. */
    public static final int DEFAULT_PREFIX_LENGTH = FuzzyQuery.defaultPrefixLength;

    /** Default maximum number of terms that the fuzzy query will expand to. Defaults to 50. */
    public static final int DEFAULT_MAX_EXPANSIONS = FuzzyQuery.defaultMaxExpansions;

    /** Default as to whether transpositions should be treated as a primitive edit operation,
     * instead of classic Levenshtein algorithm. Defaults to false. */
    public static final boolean DEFAULT_TRANSPOSITIONS = false;

    private final String fieldName;

    private final Object value;

    private Fuzziness fuzziness = DEFAULT_FUZZINESS;

    private int prefixLength = DEFAULT_PREFIX_LENGTH;

    private int maxExpansions = DEFAULT_MAX_EXPANSIONS;

    //LUCENE 4 UPGRADE  we need a testcase for this + documentation
    private boolean transpositions = DEFAULT_TRANSPOSITIONS;

    private String rewrite;

    static final FuzzyQueryBuilder PROTOTYPE = new FuzzyQueryBuilder();

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, String value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, int value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, long value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, float value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, double value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the text
     */
    public FuzzyQueryBuilder(String fieldName, boolean value) {
        this(fieldName, (Object) value);
    }

    /**
     * Constructs a new fuzzy query.
     *
     * @param fieldName  The name of the field
     * @param value The value of the term
     */
    public FuzzyQueryBuilder(String fieldName, Object value) {
        if (Strings.isEmpty(fieldName)) {
            throw new IllegalArgumentException("field name cannot be null or empty.");
        }
        if (value == null) {
            throw new IllegalArgumentException("query value cannot be null");
        }
        this.fieldName = fieldName;
        this.value = convertToBytesRefIfString(value);
    }

    private FuzzyQueryBuilder() {
        // for protoype
        this.fieldName = null;
        this.value = null;
    }

    public String fieldName() {
        return this.fieldName;
    }

    public Object value() {
        return convertToStringIfBytesRef(this.value);
    }

    public FuzzyQueryBuilder fuzziness(Fuzziness fuzziness) {
        this.fuzziness = (fuzziness == null) ? DEFAULT_FUZZINESS : fuzziness;
        return this;
    }

    public Fuzziness fuzziness() {
        return this.fuzziness;
    }

    public FuzzyQueryBuilder prefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
        return this;
    }

    public int prefixLength() {
        return this.prefixLength;
    }

    public FuzzyQueryBuilder maxExpansions(int maxExpansions) {
        this.maxExpansions = maxExpansions;
        return this;
    }

    public int maxExpansions() {
        return this.maxExpansions;
    }

    public FuzzyQueryBuilder transpositions(boolean transpositions) {
      this.transpositions = transpositions;
      return this;
    }

    public boolean transpositions() {
        return this.transpositions;
    }

    public FuzzyQueryBuilder rewrite(String rewrite) {
        this.rewrite = rewrite;
        return this;
    }

    public String rewrite() {
        return this.rewrite;
    }

    @Override
    public void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.startObject(fieldName);
        builder.field("value", convertToStringIfBytesRef(this.value));
        fuzziness.toXContent(builder, params);
        builder.field("prefix_length", prefixLength);
        builder.field("max_expansions", maxExpansions);
        builder.field("transpositions", transpositions);
        if (rewrite != null) {
            builder.field("rewrite", rewrite);
        }
        printBoostAndQueryName(builder);
        builder.endObject();
        builder.endObject();
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public Query doToQuery(QueryShardContext context) throws IOException {
        Query query = null;
        if (rewrite == null && context.isFilter()) {
            rewrite = QueryParsers.CONSTANT_SCORE.getPreferredName();
        }
        MappedFieldType fieldType = context.fieldMapper(fieldName);
        if (fieldType != null) {
            query = fieldType.fuzzyQuery(value, fuzziness, prefixLength, maxExpansions, transpositions);
        }
        if (query == null) {
            int maxEdits = fuzziness.asDistance(BytesRefs.toString(value));
            query = new FuzzyQuery(new Term(fieldName, BytesRefs.toBytesRef(value)), maxEdits, prefixLength, maxExpansions, transpositions);
        }
        if (query instanceof MultiTermQuery) {
            MultiTermQuery.RewriteMethod rewriteMethod = QueryParsers.parseRewriteMethod(context.parseFieldMatcher(), rewrite, null);
            QueryParsers.setRewriteMethod((MultiTermQuery) query, rewriteMethod);
        }
        return query;
    }

    @Override
    public FuzzyQueryBuilder doReadFrom(StreamInput in) throws IOException {
        FuzzyQueryBuilder fuzzyQueryBuilder = new FuzzyQueryBuilder(in.readString(), in.readGenericValue());
        fuzzyQueryBuilder.fuzziness = Fuzziness.readFuzzinessFrom(in);
        fuzzyQueryBuilder.prefixLength = in.readVInt();
        fuzzyQueryBuilder.maxExpansions = in.readVInt();
        fuzzyQueryBuilder.transpositions = in.readBoolean();
        fuzzyQueryBuilder.rewrite = in.readOptionalString();
        return fuzzyQueryBuilder;
    }

    @Override
    public void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(this.fieldName);
        out.writeGenericValue(this.value);
        this.fuzziness.writeTo(out);
        out.writeVInt(this.prefixLength);
        out.writeVInt(this.maxExpansions);
        out.writeBoolean(this.transpositions);
        out.writeOptionalString(this.rewrite);
    }

    @Override
    public int doHashCode() {
        return Objects.hash(fieldName, value, fuzziness, prefixLength, maxExpansions, transpositions, rewrite);
    }

    @Override
    public boolean doEquals(FuzzyQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName) &&
                Objects.equals(value, other.value) &&
                Objects.equals(fuzziness, other.fuzziness) &&
                Objects.equals(prefixLength, other.prefixLength) &&
                Objects.equals(maxExpansions, other.maxExpansions) &&
                Objects.equals(transpositions, other.transpositions) &&
                Objects.equals(rewrite, other.rewrite);
    }
}
