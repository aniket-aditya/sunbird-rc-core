package io.opensaber.registry.transform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import io.opensaber.registry.client.data.RequestData;
import io.opensaber.registry.client.data.ResponseData;
import io.opensaber.registry.config.Configuration;
import io.opensaber.registry.constants.ErrorCode;
import io.opensaber.registry.constants.JsonldConstants;
import io.opensaber.registry.exception.NodeMappingNotDefinedException;
import io.opensaber.registry.transform.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class JsonToJsonLDTransformer implements ITransformer<String> {

    private static JsonToJsonLDTransformer instance;
    private static Logger logger = LoggerFactory.getLogger(JsonToJsonLDTransformer.class);

    static {
        try {
            instance = new JsonToJsonLDTransformer();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private JsonNode fieldMapping;

    public JsonNode getFieldMapping() {
        return fieldMapping;
    }

    public static JsonToJsonLDTransformer getInstance() {
        return instance;
    }

    private static TypeReference<HashMap<String, Object>> typeRef = new TypeReference<HashMap<String, Object>>() {};
    private static ObjectMapper mapper = new ObjectMapper();

    private JsonToJsonLDTransformer() throws IOException {
        this.fieldMapping = loadDefaultMapping();
    }

    private JsonNode loadDefaultMapping() throws IOException {
        String mappingJson = CharStreams.toString(new InputStreamReader
                (JsonToJsonLDTransformer.class.getClassLoader().getResourceAsStream(Configuration.MAPPING_FILE), "UTF-8"));
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(mappingJson);
    }

    @Override
    public ResponseData<String> transform(RequestData<String> data) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(data.getRequestData());
        ObjectNode result = constructJsonLd(input, fieldMapping);
        String jsonldResult = mapper.writeValueAsString(result);
        return new ResponseData<>(jsonldResult);
    }

    public ObjectNode constructJsonLd(JsonNode rootDataNode, JsonNode nodeMapping)
            throws NodeMappingNotDefinedException, IOException {
        ObjectNode result = JsonNodeFactory.instance.objectNode();

        Map<String, Boolean> rootNodesInfo = new HashMap<>();
        for (Map.Entry<String, JsonNode> node : Lists.newArrayList(rootDataNode.fields())) {
            if(!node.getKey().equalsIgnoreCase("context")) {
                rootNodesInfo.put(node.getKey(), !node.getValue().path("definition").isMissingNode());
            }
        }

        for (Map.Entry<String, Boolean> rootNode : rootNodesInfo.entrySet()) {
            if (rootDataNode.path(rootNode.getKey()).getNodeType().equals(JsonNodeType.ARRAY)) {

                ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
                for (JsonNode node : rootDataNode.path(rootNode.getKey())) {
                    ObjectNode resultNode = JsonUtils.createObjectNode();
                    JsonNode rootNodeMapping = nodeMapping.path(rootNode.getKey());
                    JsonNode rootNodeMappingDefinition = rootNodeMapping.path("definition");
                    resultNode.put(JsonldConstants.TYPE, rootNodeMapping.path("type").asText());
                    iterateAndConstructElements(node, rootNodeMapping, rootNodeMappingDefinition, resultNode);
                    arrayNode.add(resultNode.deepCopy());
                }
                result.putArray(rootNode.getKey()).addAll(arrayNode);

            } else {
                JsonNode rootNodeMapping = nodeMapping.path(rootNode.getKey());
                JsonNode rootNodeMappingDefinition = rootNodeMapping.path("definition");
                result.put(JsonldConstants.TYPE, rootNodeMapping.path("type").asText());
                iterateAndConstructElements(rootDataNode.path(rootNode.getKey()), rootNodeMapping, rootNodeMappingDefinition, result);
            }
        }

        if(!nodeMapping.path("context").isMissingNode()) {
            ObjectNode contextNode = nodeMapping.path("context").deepCopy();
            result.set(JsonldConstants.CONTEXT, contextNode);
        }

        return result;

    }

    private void iterateAndConstructElements(JsonNode node, JsonNode nodeMapping,
                                             JsonNode nodeMappingDefinition, ObjectNode resultNode)
            throws NodeMappingNotDefinedException, IOException {
        Iterator<Map.Entry<String, JsonNode>> fieldIterator = node.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> dataNode = fieldIterator.next();
            if (!dataNode.getKey().equalsIgnoreCase("id")) {
                Map<String, Object> mapping = mapper.readValue(nodeMappingDefinition.path(dataNode.getKey()).toString(), typeRef);
                String dataNodeType = nodeMappingDefinition.path(dataNode.getKey()).path("type").asText();
                constructJsonElement(dataNode, mapping, dataNodeType, resultNode);
            } else {
                resultNode.put(JsonldConstants.ID, String.format("%s:%s", nodeMapping.path("prefix").asText(), dataNode.getValue().asText()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectNode constructJsonElement(Map.Entry<String, JsonNode> rootDataNode, Map<String, Object> nodeMapping,
                                                  String rootNodeType, ObjectNode result)
            throws NodeMappingNotDefinedException {

        boolean mappingIsAComplexObject = isMappingAComplexObject(nodeMapping);

        if (!nodeMapping.containsKey("definition")) {
            return processNode(rootDataNode, nodeMapping, rootNodeType, mappingIsAComplexObject, result);
        } else if (nodeMapping.containsKey("collection")) {
            return processCollectionNode(rootDataNode, nodeMapping, rootNodeType, mappingIsAComplexObject, result);
        } else {
            return constructJsonElement(rootDataNode, (HashMap<String, Object>) nodeMapping.get("definition"), rootNodeType, result);
        }

    }

    public ObjectNode processNode(Map.Entry<String, JsonNode> rootDataNode, Map<String, Object> nodeMapping,
                                         String rootNodeType, boolean mappingIsAComplexObject, ObjectNode result)
            throws NodeMappingNotDefinedException {
        ObjectNode resultNode = generateJsonldNode(rootDataNode, nodeMapping, mappingIsAComplexObject);
        if (mappingIsAComplexObject) {
            resultNode.put(JsonldConstants.TYPE, rootNodeType);
            if (!rootDataNode.getValue().path("id").isMissingNode()) {
                resultNode.put(JsonldConstants.ID, rootDataNode.getValue().path("id").asText());
            }
            result.set(rootDataNode.getKey(), resultNode.deepCopy());
        } else {
            result.setAll(resultNode.deepCopy());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public ObjectNode processCollectionNode(Map.Entry<String, JsonNode> rootDataNode, Map<String, Object> nodeMapping,
                                                   String rootNodeType, boolean mappingIsAComplexObject, ObjectNode result)
            throws NodeMappingNotDefinedException {
        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        for (JsonNode jsonNode : rootDataNode.getValue()) {
            Map.Entry<String, JsonNode> dataNode = new AbstractMap.SimpleEntry<>(rootDataNode.getKey(), jsonNode);
            ObjectNode resultNode = generateJsonldNode(dataNode,
                    (HashMap<String, Object>) nodeMapping.get("definition"), mappingIsAComplexObject);
            resultNode.put(JsonldConstants.TYPE, rootNodeType);
            if (!jsonNode.path("id").isMissingNode()) {
                resultNode.put(JsonldConstants.ID, jsonNode.path("id").asText());
            }
            arrayNode.add(resultNode.deepCopy());
        }
        result.putArray(rootDataNode.getKey()).addAll(arrayNode);
        return result;
    }

    @SuppressWarnings("unchecked")
    private ObjectNode generateJsonldNode(Map.Entry<String, JsonNode> node,
                                                Map<String, Object> nodeMapping,
                                                boolean mappingIsAComplexObject) throws NodeMappingNotDefinedException {

        ObjectNode resultNode = JsonUtils.createObjectNode();

        for (Map.Entry<String, Object> childMapping : nodeMapping.entrySet()) {

            ObjectNode leafNode = JsonUtils.createObjectNode();
            String nodeElementKey = mappingIsAComplexObject ? childMapping.getKey() : node.getKey();
            JsonNode childElementNode = mappingIsAComplexObject ? node.getValue().path(nodeElementKey) : node.getValue();
            HashMap<String, Object> nodeMappingValues = null;
            if (mappingIsAComplexObject) {
                nodeMappingValues = (HashMap<String, Object>) childMapping.getValue();
            }

            String nodeType = mappingIsAComplexObject ? nodeMappingValues.get("type").toString() : nodeMapping.get("type").toString();
            String prefix = mappingIsAComplexObject ? nodeMappingValues.get("prefix").toString() : nodeMapping.get("prefix").toString();
            String nodePrefix = String.format("%s:", prefix);

            if (nodeType.equalsIgnoreCase("opensaber:enumeration")) {
                leafNode.put(JsonldConstants.ID, nodePrefix + childElementNode.asText());
                resultNode.set(nodeElementKey, leafNode.deepCopy());
            } else if (nodeType.equalsIgnoreCase("opensaber:enumeration_array")) {
                ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
                if (childElementNode.isArray()) {
                    childElementNode.forEach(n -> arrayNode.addObject().put("@id", nodePrefix + n.asText()));
                }
                resultNode.putArray(nodeElementKey).addAll(arrayNode);
            } else if (nodeType.startsWith("xsd")) {
                leafNode.put(JsonldConstants.TYPE, nodeType);
                leafNode.set(JsonldConstants.VALUE, childElementNode);
                resultNode.set(nodeElementKey, leafNode.deepCopy());
            } else {
                logger.error("Child element node with no node type defined: " + childElementNode);
                throw new NodeMappingNotDefinedException("Node type not defined", ErrorCode.NODE_MAPPING_NOT_DEFINED);
            }
        }

        return resultNode;
    }

    public boolean isMappingAComplexObject(Map<String, Object> mapping) {
        boolean mappingIsAComplexObject = false;
        for (Object value : mapping.values()) {
            if (value instanceof HashMap) {
                mappingIsAComplexObject = true;
                break;
            }
        }
        return mappingIsAComplexObject;
    }

}
