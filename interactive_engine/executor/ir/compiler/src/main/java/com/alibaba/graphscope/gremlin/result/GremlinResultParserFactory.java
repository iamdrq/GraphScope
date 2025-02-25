/*
 * Copyright 2020 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.graphscope.gremlin.result;

import com.alibaba.graphscope.common.jna.type.FfiKeyType;
import com.alibaba.graphscope.gaia.proto.Common;
import com.alibaba.graphscope.gaia.proto.IrResult;
import com.alibaba.graphscope.gaia.proto.OuterExpression;
import com.alibaba.graphscope.gremlin.exception.GremlinResultParserException;
import com.alibaba.graphscope.gremlin.transform.alias.AliasManager;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum GremlinResultParserFactory implements GremlinResultParser {
    GRAPH_ELEMENT {
        @Override
        public Object parseFrom(IrResult.Results results) {
            IrResult.Element element = ParserUtils.getHeadEntry(results).getElement();
            Object graphElement = ParserUtils.parseElement(element);
            if (!(graphElement instanceof Element || graphElement instanceof List)) {
                throw new GremlinResultParserException(
                        "parse element should return vertex or edge or graph path");
            }
            return graphElement;
        }
    },
    SINGLE_VALUE {
        @Override
        public Object parseFrom(IrResult.Results results) {
            IrResult.Entry entry = ParserUtils.getHeadEntry(results);
            return ParserUtils.parseEntry(entry);
        }
    },
    PROJECT_VALUE {
        // values("name") -> key: head, value: "marko"
        // valueMap("name") -> key: head, value: {name, "marko"}
        // select("a").by("name") -> key: head, value: "marko"
        // select("a", "b").by("name") -> key: a, value: "marko"; key: b, value: "josh"
        // select("a", "b").by(valueMap("name")) -> key: a, value: {name, "marko"}; key: b, value:
        // {name, "josh"}
        @Override
        public Object parseFrom(IrResult.Results results) {
            logger.debug("{}", results);
            IrResult.Record record = results.getRecord();
            logger.debug("{}", record);
            Map<String, Object> projectResult = new HashMap<>();
            record.getColumnsList()
                    .forEach(
                            column -> {
                                String tag = getColumnKeyAsResultKey(column.getNameOrId());
                                Object parseEntry = ParserUtils.parseEntry(column.getEntry());
                                if (parseEntry instanceof Map) {
                                    Map projectTags = (Map) parseEntry;
                                    // return empty Map if none properties
                                    Map tagEntry =
                                            (Map)
                                                    projectResult.computeIfAbsent(
                                                            tag, k1 -> new HashMap<>());
                                    projectTags.forEach(
                                            (k, v) -> {
                                                if (!(v instanceof EmptyValue)) {
                                                    String nameOrId = null;
                                                    if (k
                                                            instanceof
                                                            List) { // valueMap("name") -> Map<["",
                                                        // "name"], value>
                                                        nameOrId = (String) ((List) k).get(1);
                                                    } else if (k
                                                            instanceof
                                                            String) { // valueMap() -> Map<"name",
                                                        // value>
                                                        nameOrId = (String) k;
                                                    } else if (k
                                                            instanceof
                                                            Number) { // valueMap() -> Map<1, value>
                                                        nameOrId = String.valueOf(k);
                                                    }
                                                    if (nameOrId == null || nameOrId.isEmpty()) {
                                                        throw new GremlinResultParserException(
                                                                "map value should have property"
                                                                        + " key");
                                                    }
                                                    String property = getPropertyName(nameOrId);
                                                    tagEntry.put(
                                                            property, Collections.singletonList(v));
                                                }
                                            });
                                } else {
                                    if (!(parseEntry instanceof EmptyValue)) {
                                        projectResult.put(tag, parseEntry);
                                    }
                                }
                            });
            if (projectResult.isEmpty()) {
                return EmptyValue.INSTANCE;
            } else if (projectResult.size() == 1) {
                return projectResult.entrySet().iterator().next().getValue();
            } else {
                return projectResult;
            }
        }

        // a_1 -> a, i.e. g.V().as("a").select("a")
        // name_1 -> name, i.e. g.V().values("name")
        // a_name_1 -> a, i.e. g.V().as("a").select("a").by("name")
        private String getColumnKeyAsResultKey(OuterExpression.NameOrId columnKey) {
            if (columnKey.getItemCase() == OuterExpression.NameOrId.ItemCase.ITEM_NOT_SET) {
                return "";
            }
            switch (columnKey.getItemCase()) {
                case ITEM_NOT_SET:
                    return "";
                case NAME:
                    String key = columnKey.getName();
                    return AliasManager.getPrefix(key);
                case ID:
                    return String.valueOf(columnKey.getId());
                default:
                    throw new GremlinResultParserException(columnKey.getItemCase() + " is invalid");
            }
        }

        // propertyId is in String format, i.e. "1"
        private String getPropertyName(String nameOrId) {
            OuterExpression.NameOrId.Builder builder = OuterExpression.NameOrId.newBuilder();
            if (nameOrId.matches("^[0-9]+$")) {
                builder.setId(Integer.valueOf(nameOrId));
            } else {
                builder.setName(nameOrId);
            }
            return ParserUtils.getKeyName(builder.build(), FfiKeyType.Column);
        }
    },
    GROUP {
        @Override
        public Object parseFrom(IrResult.Results results) {
            logger.debug("{}", results);
            IrResult.Record record = results.getRecord();
            Object key = null;
            Object value = null;
            for (IrResult.Column column : record.getColumnsList()) {
                OuterExpression.NameOrId columnName = column.getNameOrId();
                if (columnName.getItemCase() != OuterExpression.NameOrId.ItemCase.NAME) {
                    throw new GremlinResultParserException(
                            "column key in group should be ItemCase.NAME");
                }
                String alias = columnName.getName();
                Object parseEntry = ParserUtils.parseEntry(column.getEntry());
                if (parseEntry instanceof EmptyValue) continue;
                if (AliasManager.isGroupKeysPrefix(alias)) {
                    key = parseEntry;
                } else {
                    value = parseEntry;
                }
            }
            // if value is null then ignore, i.e.
            // g.V().values("age").group() => {35=[35], 27=[27], 32=[32], 29=[29]}
            if (value == null) return EmptyValue.INSTANCE;
            // if key is null then output null key with the corresponding value, i.e.
            // g.V().group().by("age") => {29=[v[1]], null=[v[72057594037927939],
            // v[72057594037927941]], 27=[v[2]], 32=[v[4]], 35=[v[6]]}
            Map data = new HashMap();
            data.put(key, value);
            return data;
        }
    },
    UNION {
        @Override
        public Object parseFrom(IrResult.Results results) {
            GremlinResultParser resultParser = inferFromIrResults(results);
            return resultParser.parseFrom(results);
        }

        // try to infer from the results
        private GremlinResultParser inferFromIrResults(IrResult.Results results) {
            int columns = results.getRecord().getColumnsList().size();
            logger.debug("result is {}", results);
            if (columns == 1) {
                IrResult.Entry entry = ParserUtils.getHeadEntry(results);
                switch (entry.getInnerCase()) {
                    case ELEMENT:
                        IrResult.Element element = entry.getElement();
                        if (element.getInnerCase() == IrResult.Element.InnerCase.VERTEX
                                || element.getInnerCase() == IrResult.Element.InnerCase.EDGE
                                || element.getInnerCase()
                                        == IrResult.Element.InnerCase.GRAPH_PATH) {
                            return GRAPH_ELEMENT;
                        } else if (element.getInnerCase() == IrResult.Element.InnerCase.OBJECT) {
                            Common.Value value = element.getObject();
                            if (value.getItemCase()
                                    == Common.Value.ItemCase.PAIR_ARRAY) { // project
                                return PROJECT_VALUE;
                            } else { // simple type
                                return SINGLE_VALUE;
                            }
                        } else {
                            throw new GremlinResultParserException(
                                    element.getInnerCase() + " is invalid");
                        }
                    case COLLECTION: // path()
                    default:
                        throw new GremlinResultParserException(
                                entry.getInnerCase() + " is unsupported yet");
                }
            } else if (columns > 1) { // project or group
                IrResult.Column column = results.getRecord().getColumnsList().get(0);
                OuterExpression.NameOrId columnName = column.getNameOrId();
                if (columnName.getItemCase() == OuterExpression.NameOrId.ItemCase.NAME) {
                    String name = columnName.getName();
                    if (AliasManager.isGroupKeysPrefix(name)
                            || AliasManager.isGroupValuesPrefix(name)) {
                        return GROUP;
                    } else {
                        return PROJECT_VALUE;
                    }
                } else {
                    throw new GremlinResultParserException(
                            "column key should be ItemCase.NAME to differentiate between group and"
                                    + " project");
                }
            } else {
                throw new GremlinResultParserException("columns should not be empty");
            }
        }
    },
    SUBGRAPH {
        @Override
        public Object parseFrom(IrResult.Results results) {
            return EmptyValue.INSTANCE;
        }
    };

    private static Logger logger = LoggerFactory.getLogger(GremlinResultParserFactory.class);
}
