/*
 * Copyright DataStax, Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dse.driver.api.core.graph;

import static com.datastax.oss.driver.api.core.type.DataTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.dse.driver.api.core.DseSession;
import com.datastax.dse.driver.api.core.data.geometry.LineString;
import com.datastax.dse.driver.api.core.data.geometry.Point;
import com.datastax.dse.driver.api.core.data.geometry.Polygon;
import com.datastax.dse.driver.api.core.graph.predicates.Geo;
import com.datastax.dse.driver.api.core.type.DseDataTypes;
import com.datastax.dse.driver.api.testinfra.session.DseSessionRule;
import com.datastax.dse.driver.api.testinfra.session.DseSessionRuleBuilder;
import com.datastax.dse.driver.internal.core.graph.GraphProtocol;
import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.datastax.oss.driver.api.testinfra.ccm.CustomCcmRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

public abstract class NativeGraphDataTypeITBase {

  private static CustomCcmRule ccmRule =
      CustomCcmRule.builder()
          .withDseWorkloads("graph")
          .withDseConfiguration(
              "graph.gremlin_server.scriptEngines.gremlin-groovy.config.sandbox_enabled", "false")
          .withDseConfiguration("graph.max_query_params", 32)
          .build();

  private static DseSessionRule sessionRule =
      new DseSessionRuleBuilder(ccmRule)
          .withCreateGraph()
          .withGraphProtocol(GraphProtocol.GRAPH_BINARY_1_0.toInternalCode())
          .withCoreEngine()
          .build();

  @ClassRule public static TestRule chain = RuleChain.outerRule(ccmRule).around(sessionRule);

  protected DseSession session() {
    return sessionRule.session();
  }

  protected String graphName() {
    return sessionRule.getGraphName();
  }

  @Test
  public void should_create_and_retrieve_correct_data_with_types() {
    DseSession session = session();

    // use CQL to create type for now because DSP-17567 is not in yet, so this is more stable
    session.execute(
        String.format(
            "CREATE TYPE %s.udt_graphbinary(simple text, complex tuple<int, text>, missing text)",
            graphName()));

    session.execute(
        String.format(
            "CREATE TYPE %s.udt_graphbinarygeo(point 'PointType', line 'LineStringType', poly 'PolygonType')",
            graphName()));

    ImmutableMap.Builder<String, Object> properties =
        ImmutableMap.<String, Object>builder()
            .put("Ascii", "test")
            .put("Bigint", 5L)
            .put("Boolean", true)
            .put("Date", LocalDate.of(2007, 7, 7))
            .put("Decimal", BigDecimal.valueOf(2.3))
            .put("Double", 4.5d)
            .put("Float", 4.8f)
            .put("Int", 45)
            .put("Smallint", (short) 1)
            .put("Text", "test")
            .put("Time", LocalTime.now())
            .put("Timeuuid", Uuids.timeBased())
            .put("Timestamp", Instant.now())
            .put("Uuid", java.util.UUID.randomUUID())
            .put("Varint", BigInteger.valueOf(3234))
            .put("Blob", ByteBuffer.wrap(new byte[] {1, 2, 3}))
            .put("Tinyint", (byte) 38)
            .put("listOf(Int)", Arrays.asList(2, 3, 4))
            .put("setOf(Int)", Sets.newHashSet(2, 3, 4))
            .put("mapOf(Int, Text)", ImmutableMap.of(2, "two", 4, "four"))
            .put("Duration", CqlDuration.newInstance(1, 2, 3))
            .put("LineString", Geo.lineString(1, 2, 3, 4, 5, 6))
            .put("Point", Geo.point(3, 4))
            .put("Polygon", Geo.polygon(Geo.point(3, 4), Geo.point(5, 4), Geo.point(6, 6)))
            .put("tupleOf(Int, Text)", tupleOf(INT, TEXT).newValue(5, "Bar"))
            .put(
                "typeOf('udt_graphbinary')",
                session
                    .getMetadata()
                    .getKeyspace(graphName())
                    .get()
                    .getUserDefinedType("udt_graphbinary")
                    .get()
                    .newValue(
                        "some text", tupleOf(INT, TEXT).newValue(5, "Bar"), "some missing text"))
            .put(
                "typeOf('udt_graphbinarygeo')",
                session
                    .getMetadata()
                    .getKeyspace(graphName())
                    .get()
                    .getUserDefinedType("udt_graphbinarygeo")
                    .get()
                    .newValue(
                        Point.fromCoordinates(3.3, 4.4),
                        LineString.fromPoints(
                            Point.fromCoordinates(1, 1),
                            Point.fromCoordinates(2, 2),
                            Point.fromCoordinates(3, 3)),
                        Polygon.fromPoints(
                            Point.fromCoordinates(3, 4),
                            Point.fromCoordinates(5, 4),
                            Point.fromCoordinates(6, 6))));

    TupleType tuple = tupleOf(DseDataTypes.POINT, DseDataTypes.LINE_STRING, DseDataTypes.POLYGON);
    tuple.attach(session.getContext());

    properties.put(
        "tupleOf(Point, LineString, Polygon)",
        tuple.newValue(
            Point.fromCoordinates(3.3, 4.4),
            LineString.fromPoints(
                Point.fromCoordinates(1, 1),
                Point.fromCoordinates(2, 2),
                Point.fromCoordinates(3, 3)),
            Polygon.fromPoints(
                Point.fromCoordinates(3, 4),
                Point.fromCoordinates(5, 4),
                Point.fromCoordinates(6, 6))));

    int vertexID = 1;
    String vertexLabel = "graphBinaryAllTypes";

    runTest(properties.build(), vertexLabel, vertexID);
  }

  @Test
  public void should_insert_and_retrieve_nested_UDTS_and_tuples() {
    DseSession session = session();

    // use CQL to create type for now because DSP-17567 is not in yet, so this is more stable
    session.execute(
        String.format("CREATE TYPE %s.udt1(" + "a int" + ", b text" + ")", graphName()));

    session.execute(
        String.format(
            "CREATE TYPE %s.udt2("
                + "a int"
                + ", b text"
                + ", c frozen<udt1>"
                + ", mylist list<bigint>"
                + ", mytuple_withlist tuple<varchar, tuple<bigint, frozen<list<bigint>>>>"
                + ")",
            graphName()));

    session.execute(
        String.format(
            "CREATE TYPE %s.udt3("
                + "a list<int>"
                + ", b set<float>"
                + ", c map<text, bigint>"
                + ", d list<frozen<list<double>>>"
                + ", e set<frozen<set<float>>>"
                + ", f list<frozen<tuple<int, text>>>"
                + ")",
            graphName()));

    UserDefinedType udt1 =
        session.getMetadata().getKeyspace(graphName()).get().getUserDefinedType("udt1").get();
    UdtValue udtValue1 = udt1.newValue(1, "2");

    UserDefinedType udt2 =
        session.getMetadata().getKeyspace(graphName()).get().getUserDefinedType("udt2").get();
    TupleType secondNested = tupleOf(BIGINT, listOf(BIGINT));
    TupleType firstNested = tupleOf(TEXT, secondNested);
    UdtValue udtValue2 =
        udt2.newValue(
            1,
            "2",
            udt1.newValue(3, "4"),
            ImmutableList.of(5L),
            firstNested.newValue("6", secondNested.newValue(7L, ImmutableList.of(8L))));

    UserDefinedType udt3 =
        session.getMetadata().getKeyspace(graphName()).get().getUserDefinedType("udt3").get();
    UdtValue udtValue3 =
        udt3.newValue(
            ImmutableList.of(1),
            ImmutableSet.of(2.1f),
            ImmutableMap.of("3", 4L),
            ImmutableList.of(ImmutableList.of(5.1d, 6.1d), ImmutableList.of(7.1d)),
            ImmutableSet.of(ImmutableSet.of(8.1f), ImmutableSet.of(9.1f)),
            ImmutableList.of(tupleOf(INT, TEXT).newValue(10, "11")));

    Map<String, Object> properties =
        ImmutableMap.<String, Object>builder()
            .put("frozen(typeOf('udt1'))", udtValue1)
            .put("frozen(typeOf('udt2'))", udtValue2)
            .put("frozen(typeOf('udt3'))", udtValue3)
            .build();

    int vertexID = 1;
    String vertexLabel = "graphBinaryNestedTypes";

    runTest(properties, vertexLabel, vertexID);
  }

  private void runTest(Map<String, Object> properties, String vertexLabel, int vertexID) {
    // setup schema
    session().execute(createVertexLabelStatement(properties, vertexLabel));

    // execute insert query and read query
    Map<Object, Object> results = insertVertexThenReadProperties(properties, vertexID, vertexLabel);

    // test valid properties are returned
    properties.forEach((k, v) -> assertThat(results.get(formatPropertyName(k))).isEqualTo(v));
  }

  private static GraphStatement createVertexLabelStatement(
      Map<String, Object> properties, String vertexLabel) {
    StringBuilder ddl =
        new StringBuilder("schema.vertexLabel(vertexLabel).ifNotExists().partitionBy('id', Int)");

    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String typeDefinition = entry.getKey();
      String propName = formatPropertyName(typeDefinition);

      ddl.append(String.format(".property('%s', %s)", propName, typeDefinition));
    }
    ddl.append(".create()");

    return ScriptGraphStatement.newInstance(ddl.toString())
        .setQueryParam("vertexLabel", vertexLabel);
  }

  protected abstract Map<Object, Object> insertVertexThenReadProperties(
      Map<String, Object> properties, int vertexID, String vertexLabel);

  protected static String formatPropertyName(String originalName) {
    return String.format(
        "prop%s",
        originalName.replace("(", "").replace(")", "").replace(", ", "").replace("'", ""));
  }
}
