/*
 * Copyright 2014-2014 Commonwealth Computer Research, Inc.
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

package org.locationtech.geomesa.core.index.strategies

import java.util.Date
import java.util.Map.Entry

import com.typesafe.scalalogging.slf4j.Logging
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.data.{Key, Range => AccRange, Value}
import org.apache.hadoop.io.Text
import org.geotools.data.Query
import org.geotools.temporal.`object`.DefaultPeriod
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.data.tables.{AttributeTable, RecordTable}
import org.locationtech.geomesa.core.filter._
import org.locationtech.geomesa.core.index.FilterHelper._
import org.locationtech.geomesa.core.index._
import org.locationtech.geomesa.core.index.strategies.AttributeIndexStrategy._
import org.locationtech.geomesa.core.iterators._
import org.locationtech.geomesa.core.util.{BatchMultiScanner, SelfClosingIterator}
import org.locationtech.geomesa.feature.FeatureEncoding.FeatureEncoding
import org.locationtech.geomesa.feature.SimpleFeatureDecoder
import org.locationtech.geomesa.utils.geotools.Conversions.RichAttributeDescriptor
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.expression.{Literal, PropertyName}
import org.opengis.filter.temporal.{After, Before, During, TEquals}
import org.opengis.filter.{Filter, PropertyIsEqualTo, PropertyIsLike, _}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

trait AttributeIdxStrategy extends Strategy with Logging {

  import org.locationtech.geomesa.core.index.strategies.Strategy._

  /**
   * Perform scan against the Attribute Index Table and get an iterator returning records from the Record table
   */
  def attrIdxQuery(query: Query,
                   featureType: SimpleFeatureType,
                   featureEncoding: FeatureEncoding,
                   acc: AccumuloConnectorCreator,
                   attributeName: String,
                   range: AccRange,
                   output: ExplainerOutputType): SelfClosingIterator[Entry[Key, Value]] = {

    output(s"Scanning attribute table for feature type ${featureType.getTypeName}")
    output(s"Range: ${ExplainerOutputType.toString(range)}")
    output(s"Filter: ${query.getFilter}")

    val attrScanner = acc.createAttrIdxScanner(featureType)
    attrScanner.setRange(range)

    val (geomFilters, otherFilters) = partitionGeom(query.getFilter, featureType)
    val (temporalFilters, nonSTFilters) = partitionTemporal(otherFilters, getDtgFieldName(featureType))

    output(s"Geometry filters: $geomFilters")
    output(s"Temporal filters: $temporalFilters")
    output(s"Other filters: $nonSTFilters")

    val stFilter: Option[Filter] = filterListAsAnd(geomFilters ++ temporalFilters)
    val ecqlFilter: Option[Filter] = nonSTFilters.map(_ => recomposeAnd(nonSTFilters)).headOption

    // choose which iterator we want to use - joining iterator or attribute only iterator
    val iteratorChoice: IteratorConfig =
      IteratorTrigger.chooseAttributeIterator(ecqlFilter, query, featureType, attributeName)

    val iter = iteratorChoice.iterator match {
      case IndexOnlyIterator =>
        // the attribute index iterator also handles transforms and date/geom filters
        val cfg = configureAttributeIndexIterator(featureType, featureEncoding, query, stFilter,
          iteratorChoice.transformCoversFilter, attributeName)
        attrScanner.addScanIterator(cfg)
        output(s"AttributeIndexIterator: ${cfg.toString }")

        // if this is a request for unique attribute values, add the skipping iterator to speed up response
        if (query.getHints.containsKey(GEOMESA_UNIQUE)) {
          val uCfg = configureUniqueAttributeIterator()
          attrScanner.addScanIterator(uCfg)
          output(s"UniqueAttributeIterator: ${uCfg.toString }")
        }

        // there won't be any non-date/time-filters if the index only iterator has been selected
        SelfClosingIterator(attrScanner)

      case RecordJoinIterator =>
        output("Using record join iterator")
        stFilter.foreach { filter =>
          // apply a filter for the indexed date and geometry
          val cfg = configureSpatioTemporalFilter(featureType, featureEncoding, stFilter)
          attrScanner.addScanIterator(cfg)
          output(s"SpatioTemporalFilter: ${cfg.toString }")
        }

        val recordScanner = acc.createRecordScanner(featureType)

        if (iteratorChoice.hasTransformOrFilter) {
          // apply an iterator for any remaining transforms/filters
          // TODO apply optimization for when transforms cover filter
          val cfg = configureRecordTableIterator(featureType, featureEncoding, ecqlFilter, query)
          recordScanner.addScanIterator(cfg)
          output(s"RecordTableIterator: ${cfg.toString }")
        }

        // function to join the attribute index scan results to the record table
        // since the row id of the record table is in the CF just grab that
        val prefix = getTableSharingPrefix(featureType)
        val joinFunction = (kv: java.util.Map.Entry[Key, Value]) =>
          new AccRange(RecordTable.getRowKey(prefix, kv.getKey.getColumnQualifier.toString))
        val bms = new BatchMultiScanner(attrScanner, recordScanner, joinFunction)

        SelfClosingIterator(bms.iterator, () => bms.close())
    }

    // wrap with a de-duplicator if the attribute could have multiple values, and it won't be
    // de-duped by the query planner
    if (!IndexSchema.mayContainDuplicates(featureType) &&
        featureType.getDescriptor(attributeName).isMultiValued) {
      val returnSft = Option(query.getHints.get(TRANSFORM_SCHEMA).asInstanceOf[SimpleFeatureType])
          .getOrElse(featureType)
      val decoder = SimpleFeatureDecoder(returnSft, featureEncoding)
      val deduper = new DeDuplicatingIterator(iter, (_: Key, value: Value) => decoder.extractFeatureId(value.get))
      SelfClosingIterator(deduper)
    } else {
      iter
    }
  }

  private def configureAttributeIndexIterator(
      featureType: SimpleFeatureType,
      encoding: FeatureEncoding,
      query: Query,
      stFilter: Option[Filter],
      needsTransform: Boolean,
      attributeName: String) = {

    // the attribute index iterator also checks any ST filters
    val cfg = new IteratorSetting(
      iteratorPriority_AttributeIndexIterator,
      classOf[AttributeIndexIterator].getSimpleName,
      classOf[AttributeIndexIterator]
    )

    configureFeatureTypeName(cfg, featureType.getTypeName)
    configureFeatureEncoding(cfg, encoding)
    configureStFilter(cfg, stFilter)
    configureAttributeName(cfg, attributeName)
    configureIndexValues(cfg, featureType)
    if (needsTransform) {
      // we have to evaluate the filter against full feature then apply the transform
      configureFeatureType(cfg, featureType)
      configureTransforms(cfg, query)
    } else {
      // we can evaluate the filter against the transformed schema, so skip the original feature decoding
      val transformedType = query.getHints.get(TRANSFORM_SCHEMA).asInstanceOf[SimpleFeatureType]
      configureFeatureType(cfg, transformedType)
    }

    cfg
  }

  private def configureSpatioTemporalFilter(
      featureType: SimpleFeatureType,
      encoding: FeatureEncoding,
      stFilter: Option[Filter]) = {

    // a filter applied to the attribute table to check ST filters
    val cfg = new IteratorSetting(
      iteratorPriority_AttributeIndexFilteringIterator,
      classOf[IndexedSpatioTemporalFilter].getSimpleName,
      classOf[IndexedSpatioTemporalFilter]
    )

    configureFeatureType(cfg, featureType)
    configureFeatureTypeName(cfg, featureType.getTypeName)
    configureIndexValues(cfg, featureType)
    configureFeatureEncoding(cfg, encoding)
    configureStFilter(cfg, stFilter)

    cfg
  }

  private def configureUniqueAttributeIterator() =
    // needs to be applied *after* the AttributeIndexIterator
    new IteratorSetting(
      iteratorPriority_AttributeUniqueIterator,
      classOf[UniqueAttributeIterator].getSimpleName,
      classOf[UniqueAttributeIterator]
    )
}

class AttributeIdxEqualsStrategy(val attributeFilter: Filter, val range: AccRange, val prop: String)
    extends AttributeIdxStrategy {

  override def execute(query: Query,
                       featureType: SimpleFeatureType,
                       indexSchema: String,
                       featureEncoding: FeatureEncoding,
                       acc: AccumuloConnectorCreator,
                       output: ExplainerOutputType): SelfClosingIterator[Entry[Key, Value]] = {
    val strippedQuery = removeFilter(query, attributeFilter)
    attrIdxQuery(strippedQuery, featureType, featureEncoding, acc, prop, range, output)
  }
}

class AttributeIdxRangeStrategy(val attributeFilter: Filter, val range: AccRange, val prop: String)
    extends AttributeIdxStrategy {

  override def execute(query: Query,
                       featureType: SimpleFeatureType,
                       indexSchema: String,
                       featureEncoding: FeatureEncoding,
                       acc: AccumuloConnectorCreator,
                       output: ExplainerOutputType): SelfClosingIterator[Entry[Key, Value]] = {
    val strippedQuery = removeFilter(query, attributeFilter)
    attrIdxQuery(strippedQuery, featureType, featureEncoding, acc, prop, range, output)
  }
}

class AttributeIdxLikeStrategy(val attributeFilter: Filter, val range: AccRange, val prop: String)
    extends AttributeIdxStrategy {

  override def execute(query: Query,
                       featureType: SimpleFeatureType,
                       indexSchema: String,
                       featureEncoding: FeatureEncoding,
                       acc: AccumuloConnectorCreator,
                       output: ExplainerOutputType): SelfClosingIterator[Entry[Key, Value]] = {
    val strippedQuery = removeFilter(query, attributeFilter)
    attrIdxQuery(strippedQuery, featureType, featureEncoding, acc, prop, range, output)
  }
}

/**
 * Queries against the attribute index table
 */
object AttributeIndexStrategy extends StrategyProvider {

  import org.locationtech.geomesa.utils.geotools.Conversions._

  override def getStrategy(filter: Filter, sft: SimpleFeatureType, hints: StrategyHints = NoopHints) = {
    val indexed: (PropertyLiteral) => Boolean = (p: PropertyLiteral) => sft.getDescriptor(p.name).isIndexed
    filter match {
      // equals strategy checks
      case f: PropertyIsEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val value = property.literal.getValue
          val range = AccRange.exact(getEncodedAttrIdxRow(sft, property.name, value))
          val strategy = new AttributeIdxEqualsStrategy(filter, range, property.name)
          val cost = hints.attributeCost(sft.getDescriptor(property.name), value, value)
          StrategyDecision(strategy, cost)
        }

      case f: TEquals =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val value = property.literal.getValue
          val exact = getEncodedAttrIdxRow(sft, property.name, value)
          val range = AccRange.exact(exact)
          val strategy = new AttributeIdxEqualsStrategy(filter, range, property.name)
          val cost = hints.attributeCost(sft.getDescriptor(property.name), value, value)
          StrategyDecision(strategy, cost)
        }

      case f: PropertyIsNil =>
        val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
        val descriptor = sft.getDescriptor(prop)
        if (descriptor.isIndexed) {
          val rowIdPrefix = org.locationtech.geomesa.core.index.getTableSharingPrefix(sft)
          val exact = AttributeTable.getAttributeIndexRows(rowIdPrefix, descriptor, None).head
          val range = AccRange.exact(exact)
          val strategy = new AttributeIdxEqualsStrategy(filter, range, prop)
          val cost = hints.attributeCost(descriptor, None, None)
          Some(StrategyDecision(strategy, cost))
        } else {
          None
        }

      case f: PropertyIsNull =>
        val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
        val descriptor = sft.getDescriptor(prop)
        if (descriptor.isIndexed) {
          val rowIdPrefix = org.locationtech.geomesa.core.index.getTableSharingPrefix(sft)
          val exact = AttributeTable.getAttributeIndexRows(rowIdPrefix, descriptor, None).head
          val range = AccRange.exact(exact)
          val strategy = new AttributeIdxEqualsStrategy(filter, range, prop)
          val cost = hints.attributeCost(descriptor, None, None)
          Some(StrategyDecision(strategy, cost))
        } else {
          None
        }

      // like strategy checks
      case f: PropertyIsLike =>
        val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
        val descriptor = sft.getDescriptor(prop)
        if (descriptor.isIndexed && QueryStrategyDecider.likeEligible(f)) {
          // Remove the trailing wildcard and create a range prefix
          val literal = f.getLiteral
          val value = if (literal.endsWith(QueryStrategyDecider.MULTICHAR_WILDCARD)) {
            literal.substring(0, literal.length - QueryStrategyDecider.MULTICHAR_WILDCARD.length)
          } else {
            literal
          }
          val range = AccRange.prefix(getEncodedAttrIdxRow(sft, prop, value))
          val strategy = new AttributeIdxLikeStrategy(filter, range, prop)
          val cost = hints.attributeCost(descriptor, value, value + "~~~")
          Some(StrategyDecision(strategy, cost))
        } else {
          None
        }

      // range strategy checks
      case f: PropertyIsBetween =>
        val prop = f.getExpression.asInstanceOf[PropertyName].getPropertyName
        val descriptor = sft.getDescriptor(prop)
        if (descriptor.isIndexed) {
          val lower = f.getLowerBoundary.asInstanceOf[Literal].getValue
          val upper = f.getUpperBoundary.asInstanceOf[Literal].getValue
          val lowerBound = getEncodedAttrIdxRow(sft, prop, lower)
          val upperBound = getEncodedAttrIdxRow(sft, prop, upper)
          val range = new AccRange(lowerBound, true, upperBound, true)
          val strategy = new AttributeIdxRangeStrategy(filter, range, prop)
          val cost = hints.attributeCost(descriptor, lower, upper)
          Some(StrategyDecision(strategy, cost))
        } else {
          None
        }

      case f: PropertyIsGreaterThan =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            lessThanRange(sft, property.name, property.literal.getValue)
          } else {
            greaterThanRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), "", property.literal.getValue)
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.getValue, "~~~")
          }
          StrategyDecision(strategy, cost)
        }

      case f: PropertyIsGreaterThanOrEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            lessThanOrEqualRange(sft, property.name, property.literal.getValue)
          } else {
            greaterThanOrEqualRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), "", property.literal.getValue)
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.getValue, "~~~")
          }
          StrategyDecision(strategy, cost)
        }

      case f: PropertyIsLessThan =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            greaterThanRange(sft, property.name, property.literal.getValue)
          } else {
            lessThanRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.getValue, "~~~")
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), "", property.literal.getValue)
          }
          StrategyDecision(strategy, cost)
        }

      case f: PropertyIsLessThanOrEqualTo =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            greaterThanOrEqualRange(sft, property.name, property.literal.getValue)
          } else {
            lessThanOrEqualRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.getValue, "~~~")
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), "", property.literal.getValue)
          }
          StrategyDecision(strategy, cost)
        }

      case f: Before =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            greaterThanRange(sft, property.name, property.literal.getValue)
          } else {
            lessThanRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.evaluate(null, classOf[Date]),
              new Date(Long.MaxValue))
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), new Date(0), property.literal.evaluate(null, classOf[Date]))
          }
          StrategyDecision(strategy, cost)
        }

      case f: After =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val range = if (property.flipped) {
            lessThanRange(sft, property.name, property.literal.getValue)
          } else {
            greaterThanRange(sft, property.name, property.literal.getValue)
          }
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = if (property.flipped) {
            hints.attributeCost(sft.getDescriptor(property.name), new Date(0), property.literal.evaluate(null, classOf[Date]))
          } else {
            hints.attributeCost(sft.getDescriptor(property.name), property.literal.evaluate(null, classOf[Date]),
              new Date(Long.MaxValue))
          }
          StrategyDecision(strategy, cost)
        }

      case f: During =>
        checkOrder(f.getExpression1, f.getExpression2).filter(indexed).map { property =>
          val during = property.literal.getValue.asInstanceOf[DefaultPeriod]
          val lower = during.getBeginning.getPosition.getDate
          val upper = during.getEnding.getPosition.getDate
          val lowerBound = getEncodedAttrIdxRow(sft, property.name, lower)
          val upperBound = getEncodedAttrIdxRow(sft, property.name, upper)
          val range = new AccRange(lowerBound, true, upperBound, true)
          val strategy = new AttributeIdxRangeStrategy(filter, range, property.name)
          val cost = hints.attributeCost(sft.getDescriptor(property.name), lower, upper)
          StrategyDecision(strategy, cost)
        }

      // doesn't match any attribute strategy
      case _ => None
    }
  }

  /**
   * Gets a row key that can used as a range for an attribute query.
   * The attribute index encodes the type of the attribute as part of the row. This checks for
   * query literals that don't match the expected type and tries to convert them.
   *
   * @param sft
   * @param prop
   * @param value
   * @return
   */
  def getEncodedAttrIdxRow(sft: SimpleFeatureType, prop: String, value: Any): String = {
    val descriptor = sft.getDescriptor(prop)
    // the class type as defined in the SFT
    val expectedBinding = descriptor.getType.getBinding
    // the class type of the literal pulled from the query
    val actualBinding = value.getClass
    val typedValue =
      if (expectedBinding == actualBinding) {
        value
      } else if (descriptor.isCollection) {
        // we need to encode with the collection type
        SimpleFeatureTypes.getCollectionType(descriptor) match {
          case Some(collectionType) if collectionType == actualBinding => Seq(value).asJava
          case Some(collectionType) if collectionType != actualBinding =>
            Seq(AttributeTable.convertType(value, actualBinding, collectionType)).asJava
        }
      } else if (descriptor.isMap) {
        // TODO GEOMESA-454 - support querying against map attributes
        Map.empty.asJava
      } else {
        // type mismatch, encoding won't work b/c value is wrong class
        // try to convert to the appropriate class
        AttributeTable.convertType(value, actualBinding, expectedBinding)
      }

    val rowIdPrefix = org.locationtech.geomesa.core.index.getTableSharingPrefix(sft)
    // grab the first encoded row - right now there will only ever be a single item in the seq
    // eventually we may support searching a whole collection at once
    AttributeTable.getAttributeIndexRows(rowIdPrefix, descriptor, Some(typedValue)).head
  }

  private def greaterThanRange(sft: SimpleFeatureType, prop: String, lit: AnyRef): AccRange = {
    val rowIdPrefix = getTableSharingPrefix(sft)
    val start = new Text(getEncodedAttrIdxRow(sft, prop, lit))
    val endPrefix = AttributeTable.getAttributeIndexRowPrefix(rowIdPrefix, sft.getDescriptor(prop))
    val end = AccRange.followingPrefix(new Text(endPrefix))
    new AccRange(start, false, end, false)
  }

  private def greaterThanOrEqualRange(sft: SimpleFeatureType, prop: String, lit: AnyRef): AccRange = {
    val rowIdPrefix = getTableSharingPrefix(sft)
    val start = new Text(getEncodedAttrIdxRow(sft, prop, lit))
    val endPrefix = AttributeTable.getAttributeIndexRowPrefix(rowIdPrefix, sft.getDescriptor(prop))
    val end = AccRange.followingPrefix(new Text(endPrefix))
    new AccRange(start, true, end, false)
  }

  private def lessThanRange(sft: SimpleFeatureType, prop: String, lit: AnyRef): AccRange = {
    val rowIdPrefix = getTableSharingPrefix(sft)
    val start = AttributeTable.getAttributeIndexRowPrefix(rowIdPrefix, sft.getDescriptor(prop))
    val end = getEncodedAttrIdxRow(sft, prop, lit)
    new AccRange(start, false, end, false)
  }

  private def lessThanOrEqualRange(sft: SimpleFeatureType, prop: String, lit: AnyRef): AccRange = {
    val rowIdPrefix = getTableSharingPrefix(sft)
    val start = AttributeTable.getAttributeIndexRowPrefix(rowIdPrefix, sft.getDescriptor(prop))
    val end = getEncodedAttrIdxRow(sft, prop, lit)
    new AccRange(start, false, end, true)
  }

  def removeFilter(query: Query, filterToRemove: Filter): Query = {
    val filter = query.getFilter
    val newFilter = filter match {
      case and: And => filterListAsAnd(and.getChildren.filter(_ != filterToRemove)).getOrElse(Filter.INCLUDE)
      case f: Filter if (f == filterToRemove) => Filter.INCLUDE
      case f: Filter => f
    }
    val newQuery = new Query(query)
    newQuery.setFilter(newFilter)
    newQuery
  }
}