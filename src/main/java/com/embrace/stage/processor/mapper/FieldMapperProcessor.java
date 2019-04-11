/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embrace.stage.processor.mapper;

import com.google.common.base.Strings;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.SingleLaneRecordProcessor;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.el.*;
import com.streamsets.pipeline.lib.el.AggregationEL;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.util.FieldUtils;
import com.streamsets.pipeline.stage.processor.expression.ELSupport;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FieldMapperProcessor extends SingleLaneRecordProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(FieldMapperProcessor.class);
  private final FieldMapperProcessorConfig fieldMapperConfig;

  private ELEval mapperExpressionEval;
  private ELEval mapperConditionalEval;
  private ELEval aggregationEval;
  private ELVars expressionVars;

  public FieldMapperProcessor(FieldMapperProcessorConfig fieldMapperConfig) {
    this.fieldMapperConfig = fieldMapperConfig;
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues =  super.init();
    expressionVars = ELUtils.parseConstants(
        null, getContext(), Groups.MAPPER.name(), "constants", Errors.EXPR_01, issues
    );
    mapperExpressionEval = createMapperExpressionEval(getContext());
    if (!Strings.isNullOrEmpty(fieldMapperConfig.aggregationExpression)) {
      aggregationEval = createAggregationEval(getContext());
    } else {
      aggregationEval = null;
    }
    if (!Strings.isNullOrEmpty(fieldMapperConfig.conditionalExpression)) {
      this.mapperConditionalEval = createConditionalExpressionEval(getContext());
    } else {
      this.mapperConditionalEval = null;
    }

    return issues;
  }

  private ELEval createMapperExpressionEval(ELContext elContext) {
    return elContext.createELEval(
        "mapperExpression",
        com.streamsets.pipeline.lib.el.RecordEL.class,
        com.streamsets.pipeline.lib.el.FieldEL.class,
        TimeNowEL.class,
        TimeEL.class,
        ELSupport.class
    );
  }

  private ELEval createConditionalExpressionEval(ELContext elContext) {
    return elContext.createELEval(
        "conditionalExpression",
        com.streamsets.pipeline.lib.el.RecordEL.class,
        com.streamsets.pipeline.lib.el.FieldEL.class,
        TimeNowEL.class,
        TimeEL.class,
        ELSupport.class
    );
  }

  private ELEval createAggregationEval(ELContext elContext) {
    return elContext.createELEval(
        "aggregationEval",
        com.streamsets.pipeline.lib.el.RecordEL.class,
        AggregationEL.class,
        TimeNowEL.class,
        TimeEL.class,
        ELSupport.class
    );
  }

  @Override
  protected void process(Record record, SingleLaneBatchMaker batchMaker) throws StageException {
    RecordEL.setRecordInContext(expressionVars, record);
    TimeNowEL.setTimeNowInContext(expressionVars, new Date());

    switch (fieldMapperConfig.operateOn) {
      case FIELD_PATHS:
        transformFieldPaths(record);
        break;
      case FIELD_VALUES:
        transformFieldValues(record);
        break;
      case FIELD_NAMES:
        transformFieldNames(record);
        break;
      default:
        throw new IllegalStateException(String.format(
            "Unrecognized operateOn value of %s",
            fieldMapperConfig.operateOn
        ));
    }
    batchMaker.addRecord(record);
  }

  private void transformFieldValues(Record record) throws StageException {
    record.forEachField(fv -> {
      final String fieldPath = fv.getFieldPath();
      final String fieldName = fv.getFieldName();
      final Field field = fv.getField();
      if (checkSkipFieldAndSetContextVar(fieldPath, fieldName, field, true)) {
        return;
      }

      final Object newValue = mapperExpressionEval.eval(
          expressionVars,
          fieldMapperConfig.mappingExpression,
          Object.class
      );

      final Field.Type newType = FieldUtils.getTypeFromObject(newValue);

      record.set(fieldPath, Field.create(newType, newValue));
    });
  }

  private void transformFieldNames(Record record) throws StageException {
    final Map<Map<String, Field>, Map<String, String>> parentFieldToChildRenames = new HashMap<>();

    record.forEachField(fv -> {
      final String fieldPath = fv.getFieldPath();
      final String fieldName = fv.getFieldName();
      final Field field = fv.getField();
      if (checkSkipFieldAndSetContextVar(fieldPath, fieldName, field, false)) {
        return;
      }
      if (fieldMapperConfig.operateOn == OperateOn.FIELD_NAMES && fv.getParentField() != null
          && fv.getParentField().getType() == Field.Type.LIST) {
        // we are operating on field names, and the parent is a list, which means this field is an item in the list
        // don't attempt to rename this field, since it's nonsensical (the list field itself will be handled on its own
        // visit)
        return;
      }

      try {
        final String newName = mapperExpressionEval.eval(
            expressionVars,
            fieldMapperConfig.mappingExpression,
            String.class
        );

        if (!StringUtils.equals(newName, fieldName)) {
          final Field parentField = fv.getParentField();
          if (parentField == null) {
            throw new IllegalStateException(String.format(
                "parentField is null in FieldVisitor when processing field path %s",
                fieldPath
            ));
          } else {
            Map<String, Field> parentFieldMapValue;
            switch (parentField.getType()) {
              case MAP:
                parentFieldMapValue = parentField.getValueAsMap();
                break;
              case LIST_MAP:
                parentFieldMapValue = parentField.getValueAsListMap();
                break;
              default:
                throw new IllegalStateException(String.format(
                    "parentField is not a MAP or LIST_MAP in FieldVisitor when processing field path %s",
                    fieldPath
                ));
            }
            if (!parentFieldToChildRenames.containsKey(parentFieldMapValue)) {
              parentFieldToChildRenames.put(parentFieldMapValue, new LinkedHashMap<>());
            }
            parentFieldToChildRenames.get(parentFieldMapValue).put(fieldName, newName);
          }
        }
      } catch (ELEvalException e) {
        throw new RuntimeException(String.format(
            "Failed to evaluate mapper expression %s: %s",
            fieldMapperConfig.mappingExpression,
            e.getMessage()
        ), e);
      }
    });

    parentFieldToChildRenames.forEach((fieldMap, nameMapping) -> {
      nameMapping.forEach((oldName, newName) -> {
        Field field;
        if (fieldMapperConfig.maintainOriginalPaths) {
          field = fieldMap.get(oldName);
        } else {
          field = fieldMap.remove(oldName);
        }
        fieldMap.put(newName, field);
      });
    });
  }

  private void transformFieldPaths(Record record) throws StageException {
    final Map<String, List<Field>> newPathsToFields = new LinkedHashMap<>();
    final LinkedList<String> pathsToDelete = new LinkedList<>();
    final Map<Field, String> fieldsToPreviousPaths = new HashMap<>();

    record.forEachField(fv -> {
      final String fieldPath = fv.getFieldPath();
      final String fieldName = fv.getFieldName();
      final Field field = fv.getField();
      if (checkSkipFieldAndSetContextVar(fieldPath, fieldName, field, true)) {
        return;
      }
      try {
        final String newPath = mapperExpressionEval.eval(
            expressionVars,
            fieldMapperConfig.mappingExpression,
            String.class
        );

        newPathsToFields.computeIfAbsent(newPath, k -> new LinkedList<>());
        newPathsToFields.get(newPath).add(field);
      } catch (ELEvalException e) {
        throw new RuntimeException(String.format(
            "Failed to evaluate mapper expression %s: %s",
            fieldMapperConfig.mappingExpression,
            e.getMessage()
        ), e);
      }
      if (!fieldMapperConfig.maintainOriginalPaths) {
        pathsToDelete.add(fieldPath);
      }
      fieldsToPreviousPaths.put(field, fieldPath);
    });

    for (String newPath : newPathsToFields.keySet()) {
      final List<Field> mappedFields = new LinkedList<>(newPathsToFields.get(newPath));
      if (aggregationEval != null) {
        expressionVars.addVariable("fields", mappedFields);
        AggregationEL.setFieldsToPreviousPathsInContext(expressionVars, fieldsToPreviousPaths);
        final Object aggregationResult = aggregationEval.eval(
            expressionVars,
            fieldMapperConfig.aggregationExpression,
            Object.class
        );
        expressionVars.addVariable("fields", null);
        if (aggregationResult instanceof Field) {
          record.set(newPath, (Field) aggregationResult);
        } else {
          final Field.Type aggregationResultType = FieldUtils.getTypeFromObject(aggregationResult);
          record.set(newPath, Field.create(aggregationResultType, aggregationResult));
        }
      } else {
        boolean replaceValues = false;
        if (record.has(newPath)) {
          final Field existingField = record.get(newPath);
          if (existingField.getType() == Field.Type.LIST) {
            final List<Field> valueAsList = existingField.getValueAsList();
            if (!fieldMapperConfig.appendListValues) {
              valueAsList.clear();
            }
            valueAsList.addAll(mappedFields);
          } else if (fieldMapperConfig.structureChangeAllowed) {
            replaceValues = true;
          }
        } else if (fieldMapperConfig.structureChangeAllowed) {
          replaceValues = true;
        }

        if (replaceValues) {
          if (mappedFields.size() > 1) {
            record.set(newPath, Field.create(new LinkedList<>(mappedFields)));
          } else {
            record.set(newPath, mappedFields.iterator().next());
          }
        }
      }
    }
    pathsToDelete.descendingIterator().forEachRemaining(path -> record.delete(path));
  }

  private boolean checkSkipFieldAndSetContextVar(
      String fieldPath,
      String fieldName,
      Field field,
      boolean leafNodesOnly
  ) {
    if (leafNodesOnly && EnumSet.of(Field.Type.MAP, Field.Type.LIST_MAP, Field.Type.LIST).contains(field.getType())) {
      // operate only on leaf nodes
      return true;
    }
    com.streamsets.pipeline.lib.el.FieldEL.setFieldInContext(expressionVars, fieldPath, fieldName, field);
    if (mapperConditionalEval != null) {
      try {
        final boolean conditionalResult = mapperExpressionEval.eval(
            expressionVars,
            fieldMapperConfig.conditionalExpression,
            Boolean.class
        );
        if (!conditionalResult) {
          if (LOG.isTraceEnabled()) {
            LOG.trace(
                "False result of conditional expression %s against field with path %s, value %s; skipping",
                fieldPath,
                field.getValue()
            );
          }
          return true;
        }
      } catch (ELEvalException e) {
        throw new RuntimeException(String.format(
            "Failed to evaluate conditional expression %s: %s",
            fieldMapperConfig.conditionalExpression,
            e.getMessage()
        ), e);
      }
    }
    return false;
  }
}
