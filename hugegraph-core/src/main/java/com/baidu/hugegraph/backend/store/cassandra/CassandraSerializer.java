package com.baidu.hugegraph.backend.store.cassandra;

import java.util.Collection;
import java.util.Map;

import org.apache.tinkerpop.gremlin.structure.Direction;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.IdGeneratorFactory;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.serializer.AbstractSerializer;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.schema.HugeEdgeLabel;
import com.baidu.hugegraph.schema.HugeIndexLabel;
import com.baidu.hugegraph.schema.HugePropertyKey;
import com.baidu.hugegraph.schema.HugeVertexLabel;
import com.baidu.hugegraph.schema.SchemaElement;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.structure.HugeElement;
import com.baidu.hugegraph.structure.HugeIndex;
import com.baidu.hugegraph.structure.HugeProperty;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.type.HugeTypes;
import com.baidu.hugegraph.type.define.Cardinality;
import com.baidu.hugegraph.type.define.DataType;
import com.baidu.hugegraph.type.define.Frequency;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.type.define.IndexType;
import com.baidu.hugegraph.type.schema.EdgeLabel;
import com.baidu.hugegraph.type.schema.IndexLabel;
import com.baidu.hugegraph.type.schema.PropertyKey;
import com.baidu.hugegraph.type.schema.VertexLabel;
import com.google.gson.Gson;

public class CassandraSerializer extends AbstractSerializer {

    private static Gson gson = new Gson();

    public CassandraSerializer(final HugeGraph graph) {
        super(graph);
    }

    public static String toJson(Object object) {
        return gson.toJson(object);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    @Override
    public BackendEntry newBackendEntry(Id id) {
        return new CassandraBackendEntry(id);
    }

    protected CassandraBackendEntry newBackendEntry(HugeElement e) {
        return new CassandraBackendEntry(e.type(), e.id());
    }

    protected CassandraBackendEntry newBackendEntry(SchemaElement e) {
        Id id = IdGeneratorFactory.generator().generate(e);
        return new CassandraBackendEntry(e.type(), id);
    }

    protected CassandraBackendEntry newBackendEntry(HugeIndex index) {
        Id id = IdGeneratorFactory.generator().generate(index.id());
        if (index.indexType() == IndexType.SECONDARY) {
            return new CassandraBackendEntry(HugeTypes.SECONDARY_INDEX, id);
        } else {
            return new CassandraBackendEntry(HugeTypes.SEARCH_INDEX, id);
        }
    }

    @Override
    protected BackendEntry convertEntry(BackendEntry backendEntry) {
        if (backendEntry instanceof CassandraBackendEntry) {
            return backendEntry;
        } else {
            throw new BackendException(
                    "CassandraSerializer just supports CassandraBackendEntry");
        }
    }

    protected CassandraBackendEntry.Property formatProperty(HugeProperty<?> prop) {
        return new CassandraBackendEntry.Property(
                HugeKeys.PROPERTY_KEY, prop.key(),
                HugeKeys.PROPERTY_VALUE, toJson(prop.value()));
    }

    protected void parseProperty(String colName, String colValue, HugeElement owner) {
        // get PropertyKey by PropertyKey name
        PropertyKey pkey = this.graph.schema().propertyKey(colName);

        // parse value
        Object value = fromJson(colValue, pkey.clazz());

        // set properties of vertex/edge
        if (pkey.cardinality() == Cardinality.SINGLE) {
            owner.property(pkey.name(), value);
        } else {
            if (value instanceof Collection) {
                for (Object v : (Collection<?>) value) {
                    owner.property(pkey.name(), v);
                }
            } else {
                assert false : "invalid value of non-sigle property";
            }
        }
    }

    protected CassandraBackendEntry.Row formatEdge(HugeEdge edge) {
        CassandraBackendEntry.Row row = new CassandraBackendEntry.Row(
                HugeTypes.EDGE, edge.sourceVertex().id());

        // sourceVertex + direction + edge-label-name + sortValues + targetVertex
        row.key(HugeKeys.SOURCE_VERTEX, edge.owner().id().asString());
        row.key(HugeKeys.DIRECTION, edge.direction().name());
        row.key(HugeKeys.LABEL, edge.label());
        row.key(HugeKeys.SORT_VALUES, edge.name());
        row.key(HugeKeys.TARGET_VERTEX, edge.otherVertex().id().asString());

        // edge properties
        for (HugeProperty<?> property : edge.getProperties().values()) {
            row.cell(this.formatProperty(property));
        }

        // TODO: fill a default property if non, it should be improved!
        if (edge.getProperties().isEmpty()) {
            row.cell(new CassandraBackendEntry.Property(
                    HugeKeys.PROPERTY_KEY, "~exist",
                    HugeKeys.PROPERTY_VALUE, "1"));
        }

        return row;
    }

    // parse an edge from a sub row
    protected void parseEdge(CassandraBackendEntry.Row row, HugeVertex vertex) {
        @SuppressWarnings("unused")
        String sourceVertexId = row.key(HugeKeys.SOURCE_VERTEX);
        Direction direction = Direction.valueOf(row.key(HugeKeys.DIRECTION));
        String labelName = row.key(HugeKeys.LABEL);
        String targetVertexId = row.key(HugeKeys.TARGET_VERTEX);

        boolean isOutEdge = (direction == Direction.OUT);
        EdgeLabel label = this.graph.schema().edgeLabel(labelName);

        // TODO: how to construct targetVertex with id
        Id otherVertexId = IdGeneratorFactory.generator().generate(targetVertexId);
        HugeVertex otherVertex = new HugeVertex(this.graph, otherVertexId, null);

        HugeEdge edge = new HugeEdge(this.graph, null, label);

        if (isOutEdge) {
            edge.targetVertex(otherVertex);
            vertex.addOutEdge(edge);
        } else {
            edge.sourceVertex(otherVertex);
            vertex.addInEdge(edge);
        }

        // edge properties
        for (CassandraBackendEntry.Property cell : row.cells()) {
            this.parseProperty(cell.name(), cell.value(), edge);
        }

        edge.assignId();
    }

    @Override
    public BackendEntry writeVertex(HugeVertex vertex) {
        CassandraBackendEntry entry = newBackendEntry(vertex);

        entry.column(HugeKeys.LABEL, vertex.label());
        entry.column(HugeKeys.PRIMARY_VALUES, vertex.name());

        // add all properties of a Vertex
        for (HugeProperty<?> prop : vertex.getProperties().values()) {
            entry.column(this.formatProperty(prop));
        }

        // add all edges of a Vertex
        for (HugeEdge edge : vertex.getEdges()) {
            entry.subRow(this.formatEdge(edge));
        }

        return entry;
    }

    @Override
    public HugeVertex readVertex(BackendEntry bytesEntry) {
        if (bytesEntry == null) {
            return null;
        }
        bytesEntry = this.convertEntry(bytesEntry);
        assert bytesEntry instanceof CassandraBackendEntry;
        CassandraBackendEntry entry = (CassandraBackendEntry) bytesEntry;

        String labelName = entry.column(HugeKeys.LABEL);
        VertexLabel label = this.graph.schema().vertexLabel(labelName);

        // id
        HugeVertex vertex = new HugeVertex(this.graph, entry.id(), label);

        // parse all properties of a Vertex
        for (CassandraBackendEntry.Property cell : entry.cells()) {
            this.parseProperty(cell.name(), cell.value(), vertex);
        }

        // parse all edges of a Vertex
        for (CassandraBackendEntry.Row edge : entry.subRows()) {
            this.parseEdge(edge, vertex);
        }
        return vertex;
    }

    @Override
    public BackendEntry writeId(HugeTypes type, Id id) {
        // NOTE: Cassandra does not need to add type prefix for id
        return newBackendEntry(id);
    }

    @Override
    public Query writeQuery(Query query) {
        // NOTE: Cassandra does not need to add type prefix for id
        return query;
    }

    @Override
    public BackendEntry writeVertexLabel(VertexLabel vertexLabel) {
        CassandraBackendEntry entry = newBackendEntry(vertexLabel);
        entry.column(HugeKeys.NAME, vertexLabel.name());
        entry.column(HugeKeys.PRIMARY_KEYS,
                toJson(vertexLabel.primaryKeys().toArray()));
        entry.column(HugeKeys.INDEX_NAMES,
                toJson(vertexLabel.indexNames().toArray()));
        writeProperties(vertexLabel, entry);
        return entry;
    }

    @Override
    public BackendEntry writeEdgeLabel(EdgeLabel edgeLabel) {
        CassandraBackendEntry entry = newBackendEntry(edgeLabel);
        entry.column(HugeKeys.NAME, edgeLabel.name());
        entry.column(HugeKeys.FREQUENCY, toJson(edgeLabel.frequency()));
        entry.column(HugeKeys.MULTIPLICITY, toJson(edgeLabel.multiplicity()));
        // entry.column(HugeKeys.LINKS, toJson(edgeLabel.links().toArray()));
        entry.column(HugeKeys.SORT_KEYS, toJson(edgeLabel.sortKeys().toArray()));
        entry.column(HugeKeys.INDEX_NAMES,
                toJson(edgeLabel.indexNames().toArray()));
        writeProperties(edgeLabel, entry);
        return entry;
    }

    @Override
    public BackendEntry writePropertyKey(PropertyKey propertyKey) {
        CassandraBackendEntry entry = newBackendEntry(propertyKey);
        entry.column(HugeKeys.NAME, propertyKey.name());
        entry.column(HugeKeys.DATA_TYPE, toJson(propertyKey.dataType()));
        entry.column(HugeKeys.CARDINALITY, toJson(propertyKey.cardinality()));
        writeProperties(propertyKey, entry);
        return entry;
    }

    public void writeProperties(SchemaElement schemaElement, CassandraBackendEntry entry) {
        Map<String, PropertyKey> properties = schemaElement.properties();
        if (properties == null) {
            entry.column(HugeKeys.PROPERTIES, "[]");
        } else {
            entry.column(HugeKeys.PROPERTIES,
                    toJson(properties.keySet().toArray()));
        }
    }

    @Override
    public VertexLabel readVertexLabel(BackendEntry backendEntry) {
        if (backendEntry == null) {
            return null;
        }

        backendEntry = convertEntry(backendEntry);
        assert backendEntry instanceof CassandraBackendEntry;

        CassandraBackendEntry entry = (CassandraBackendEntry) backendEntry;
        String name = entry.column(HugeKeys.NAME);
        String properties = entry.column(HugeKeys.PROPERTIES);
        String primarykeys = entry.column(HugeKeys.PRIMARY_KEYS);
        String indexNames = entry.column(HugeKeys.INDEX_NAMES);

        HugeVertexLabel vertexLabel = new HugeVertexLabel(name,
                this.graph.schemaTransaction());
        vertexLabel.properties(fromJson(properties, String[].class));
        vertexLabel.primaryKeys(fromJson(primarykeys, String[].class));
        vertexLabel.indexNames(fromJson(indexNames, String[].class));

        return vertexLabel;
    }

    @Override
    public EdgeLabel readEdgeLabel(BackendEntry backendEntry) {
        if (backendEntry == null) {
            return null;
        }

        backendEntry = convertEntry(backendEntry);
        assert backendEntry instanceof CassandraBackendEntry;

        CassandraBackendEntry entry = (CassandraBackendEntry) backendEntry;
        String name = entry.column(HugeKeys.NAME);
        String frequency = entry.column(HugeKeys.FREQUENCY);
        String sortKeys = entry.column(HugeKeys.SORT_KEYS);
        String properties = entry.column(HugeKeys.PROPERTIES);
        String indexNames = entry.column(HugeKeys.INDEX_NAMES);

        HugeEdgeLabel edgeLabel = new HugeEdgeLabel(name,
                this.graph.schemaTransaction());
        edgeLabel.frequency(fromJson(frequency, Frequency.class));
        edgeLabel.properties(fromJson(properties, String[].class));
        edgeLabel.sortKeys(fromJson(sortKeys, String[].class));
        edgeLabel.indexNames(fromJson(indexNames, String[].class));
        return edgeLabel;
    }

    @Override
    public PropertyKey readPropertyKey(BackendEntry backendEntry) {
        if (backendEntry == null) {
            return null;
        }

        backendEntry = convertEntry(backendEntry);
        assert backendEntry instanceof CassandraBackendEntry;

        CassandraBackendEntry entry = (CassandraBackendEntry) backendEntry;
        String name = entry.column(HugeKeys.NAME);
        String dataType = entry.column(HugeKeys.DATA_TYPE);
        String cardinality = entry.column(HugeKeys.CARDINALITY);
        String properties = entry.column(HugeKeys.PROPERTIES);

        HugePropertyKey propertyKey = new HugePropertyKey(name,
                this.graph.schemaTransaction());
        propertyKey.dataType(fromJson(dataType, DataType.class));
        propertyKey.cardinality(fromJson(cardinality, Cardinality.class));
        propertyKey.properties(fromJson(properties, String[].class));

        return propertyKey;
    }

    @Override
    public BackendEntry writeIndexLabel(IndexLabel indexLabel) {
        CassandraBackendEntry entry = newBackendEntry(indexLabel);
        entry.column(HugeKeys.BASE_TYPE, toJson(indexLabel.baseType()));
        entry.column(HugeKeys.BASE_VALUE, toJson(indexLabel.baseValue()));
        entry.column(HugeKeys.NAME, indexLabel.name());
        entry.column(HugeKeys.INDEX_TYPE, toJson(indexLabel.indexType()));
        entry.column(HugeKeys.FIELDS, toJson(indexLabel.indexFields().toArray()));
        return entry;
    }

    @Override
    public IndexLabel readIndexLabel(BackendEntry backendEntry) {

        if (backendEntry == null) {
            return null;
        }

        backendEntry = convertEntry(backendEntry);
        assert backendEntry instanceof CassandraBackendEntry;

        CassandraBackendEntry entry = (CassandraBackendEntry) backendEntry;
        HugeTypes baseType = fromJson(entry.column(HugeKeys.BASE_TYPE), HugeTypes.class);
        String baseValue = entry.column(HugeKeys.BASE_VALUE);
        String indexName = entry.column(HugeKeys.NAME);
        String indexType = entry.column(HugeKeys.INDEX_TYPE);
        String indexFields = entry.column(HugeKeys.FIELDS);

        HugeIndexLabel indexLabel = new HugeIndexLabel(indexName, baseType, baseValue,
                this.graph.schemaTransaction());
        indexLabel.indexType(fromJson(indexType, IndexType.class));
        indexLabel.by(fromJson(indexFields, String[].class));

        return indexLabel;
    }

    @Override
    public BackendEntry writeIndex(HugeIndex index) {
        CassandraBackendEntry entry = newBackendEntry(index);
        entry.column(HugeKeys.PROPERTY_VALUES, index.propertyValues());
        entry.column(HugeKeys.INDEX_LABEL_NAME, index.indexLabelName());
        // TODO: try to make these code more clear.
        Id[] ids = index.elementIds().toArray(new Id[0]);
        assert ids.length == 1;
        entry.column(HugeKeys.ELEMENT_IDS, ids[0].asString());
        return entry;
    }

    @Override
    public HugeIndex readIndex(BackendEntry backendEntry) {
        if (backendEntry == null) {
            return null;
        }

        backendEntry = convertEntry(backendEntry);
        assert backendEntry instanceof CassandraBackendEntry;
        CassandraBackendEntry entry = (CassandraBackendEntry) backendEntry;

        String indexValues = entry.column(HugeKeys.PROPERTY_VALUES);
        String indexLabelName = entry.column(HugeKeys.INDEX_LABEL_NAME);
        String elementIds = entry.column(HugeKeys.ELEMENT_IDS);

        IndexLabel indexLabel = this.graph.schemaTransaction().getIndexLabel(indexLabelName);

        HugeIndex index = new HugeIndex(indexLabel);
        index.propertyValues(indexValues);

        String[] ids = fromJson(elementIds, String[].class);
        for (String id : ids) {
            index.elementIds(IdGeneratorFactory.generator().generate(id));
        }

        return index;
    }

}