/*
 * Copyright (2021) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta.stats

import java.util.Objects

import scala.collection.mutable

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.files.{TahoeFileIndex, TahoeLogFileIndex}
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.types.StructType

/**
 * Before query planning, we prepare any scans over delta tables by pushing
 * any projections or filters in allowing us to gather more accurate statistics
 * for CBO and metering.
 *
 * Note the following
 * - This rule also ensures that all reads from the same delta log use the same snapshot of log
 *   thus providing snapshot isolation.
 * - If this rule is invoked within an active [[OptimisticTransaction]], then the scans are
 *   generated using the transaction.
 */
trait PrepareDeltaScanBase extends Rule[LogicalPlan]
  with PredicateHelper
  with DeltaLogging { self: PrepareDeltaScan =>

  private val snapshotIsolationEnabled = spark.conf.get(DeltaSQLConf.DELTA_SNAPSHOT_ISOLATION)

  /**
   * Tracks the first-access snapshots of other logs planned by this rule. The snapshots are
   * the keyed by the log's unique id. Note that the lifetime of this rule is a single
   * query, therefore, the map tracks the snapshots only within a query.
   */
  private val scannedSnapshots =
    new java.util.concurrent.ConcurrentHashMap[(String, Path), Snapshot]

  /**
   * Gets the [[DeltaScanGenerator]] for the given log, which will be used to generate
   * [[DeltaScan]]s. Every time this method is called on a log within the lifetime of this
   * rule (i.e., the lifetime of the query for which this rule was instantiated), the returned
   * generator will read a snapshot that is pinned on the first access for that log.
   *
   * Internally, it will use the snapshot of the file index, the snapshot of the active transaction
   * (if any), or the latest snapshot of the given log.
   */
  protected def getDeltaScanGenerator(index: TahoeLogFileIndex): DeltaScanGenerator = {
    // The first case means that we've fixed the table snapshot for time travel
    if (index.isTimeTravelQuery) return index.getSnapshot
    val scanGenerator = OptimisticTransaction.getActive().map(_.getDeltaScanGenerator(index))
      .getOrElse {
        val snapshot = if (snapshotIsolationEnabled) {
          scannedSnapshots.computeIfAbsent(index.deltaLog.compositeId, _ => {
            // Will be called only when the log is accessed the first time
            index.getSnapshot
          })
        } else {
          index.getSnapshot
        }
        snapshot
      }
    scanGenerator
  }

  /**
   * Helper method to generate a [[PreparedDeltaFileIndex]]
   */
  protected def getPreparedIndex(
      preparedScan: DeltaScan,
      fileIndex: TahoeLogFileIndex): PreparedDeltaFileIndex = {
    assert(fileIndex.partitionFilters.isEmpty,
      "Partition filters should have been extracted by DeltaAnalysis.")
    PreparedDeltaFileIndex(
      spark,
      fileIndex.deltaLog,
      fileIndex.path,
      preparedScan,
      fileIndex.partitionSchema,
      fileIndex.versionToUse)
  }

  /**
   * Scan files using the given `filters` and return the snapshot object used to
   * scan files and `DeltaScan`.
   */
  protected def filesForScan(
      scanGenerator: DeltaScanGenerator,
      limitOpt: Option[Int],
      projection: Seq[Attribute],
      filters: Seq[Expression],
      delta: LogicalRelation): (Snapshot, DeltaScan) = {
    withStatusCode("DELTA", "Filtering files for query") {
      scanGenerator.snapshotToScan -> scanGenerator.filesForScan(projection, filters)
    }
  }

  /**
   * Prepares delta scans sequentially.
   */
  protected def prepareDeltaScan(plan: LogicalPlan): LogicalPlan = {
    // A map from the canonicalized form of a DeltaTableScan operator to its corresponding delta
    // scan and the snapshot we use to scan the table. This map is used to avoid fetching duplicate
    // delta indexes for structurally-equal delta scans.
    val deltaScans = new mutable.HashMap[LogicalPlan, (Snapshot, DeltaScan)]()

    /*
     * We need to first prepare the scans in the subqueries of a node. Otherwise, because of the
     * short-circuiting nature of the pattern matching in the transform method, if a
     * PhysicalOperation node is matched, its subqueries that may contain other PhysicalOperation
     * nodes will be skipped.
     */
    def transformSubqueries(plan: LogicalPlan): LogicalPlan = {
      import org.apache.spark.sql.delta.implicits._

      plan transformAllExpressionsUp {
        case subquery: SubqueryExpression =>
          subquery.withNewPlan(transform(subquery.plan))
      }
    }

    def transform(plan: LogicalPlan): LogicalPlan =
      transformSubqueries(plan) transform {
        case scan @ DeltaTableScan(projection, filters, fileIndex, limit, delta) =>
          val scanGenerator = getDeltaScanGenerator(fileIndex)
          val (scannedSnapshot, preparedScan) = deltaScans.getOrElseUpdate(scan.canonicalized,
              filesForScan(scanGenerator, limit, projection, filters, delta))
          val preparedIndex = getPreparedIndex(preparedScan, fileIndex)
          optimizeGeneratedColumns(
            scannedSnapshot, scan, preparedIndex, filters, limit, delta)
      }

    transform(plan)
  }

  protected def optimizeGeneratedColumns(
      scannedSnapshot: Snapshot,
      scan: LogicalPlan,
      preparedIndex: PreparedDeltaFileIndex,
      filters: Seq[Expression],
      limit: Option[Int],
      delta: LogicalRelation): LogicalPlan = {
    // TODO: future generated columns optimization
    DeltaTableUtils.replaceFileIndex(scan, preparedIndex)
  }

  override def apply(_plan: LogicalPlan): LogicalPlan = {
    var plan = _plan
    if (spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_STATS_SKIPPING)) {

      // Should not be applied to subqueries to avoid duplicate delta jobs.
      val isSubquery = plan.isInstanceOf[Subquery] || plan.isInstanceOf[SupportsSubquery]
      // Should not be applied to DataSourceV2 write plans, because they'll be planned later
      // through a V1 fallback and only that later planning takes place within the transaction.
      val isDataSourceV2 = plan.isInstanceOf[V2WriteCommand]
      if (isSubquery || isDataSourceV2) {
        return plan
      }

      prepareDeltaScan(plan)
    } else {
      // If this query is running inside an active transaction and is touching the same table
      // as the transaction, then mark that the entire table as tainted to be safe.
      OptimisticTransaction.getActive.foreach { txn =>
        val logsInPlan = plan.collect { case DeltaTable(fileIndex) => fileIndex.deltaLog }
        if (logsInPlan.exists(_.isSameLogAs(txn.deltaLog))) {
          txn.readWholeTable()
        }
      }

      // Just return the plan if statistics based skipping is off.
      // It will fall back to just partition pruning at planning time.
      plan
    }
  }

  /**
   * This is an extractor object. See https://docs.scala-lang.org/tour/extractor-objects.html.
   */
  object DeltaTableScan {

    /**
     * The components of DeltaTableScanType are:
     * - an `AttributeSet` of the project collected by `PhysicalOperation`
     * - filter expressions collected by `PhysicalOperation`
     * - the `TahoeLogFileIndex` of the matched DeltaTable`
     * - integer value of limit expression, if any
     * - matched `DeltaTable`
     */
    private type DeltaTableScanType =
      (Seq[Attribute], Seq[Expression], TahoeLogFileIndex, Option[Int], LogicalRelation)

    /**
     * This is an extractor method (basically, the opposite of a constructor) which takes in an
     * object `plan` and tries to give back the arguments as a [[DeltaTableScanType]].
     */
    def unapply(plan: LogicalPlan): Option[DeltaTableScanType] = {
      plan match {
        case PhysicalOperation(
            project,
            filters,
            delta @ DeltaTable(fileIndex: TahoeLogFileIndex)) =>
          val projects = AttributeSet(project).toSeq
          val allFilters = fileIndex.partitionFilters ++ filters
          Some((projects, allFilters, fileIndex, None, delta))

        case _ => None
      }
    }

    private def containsPartitionFiltersOnly(
        filters: Seq[Expression],
        fileIndex: TahoeLogFileIndex): Boolean = {
      val partitionColumns = fileIndex.deltaLog.snapshot.metadata.partitionColumns
      import DeltaTableUtils._
      filters.forall(expr => !containsSubquery(expr) &&
        isPredicatePartitionColumnsOnly(expr, partitionColumns, spark))
    }
  }
}

class PrepareDeltaScan(protected val spark: SparkSession)
  extends PrepareDeltaScanBase


/**
 * A [[TahoeFileIndex]] that uses a prepared scan to return the list of relevant files.
 * This is injected into a query right before query planning by [[PrepareDeltaScan]] so that
 * CBO and metering can accurately understand how much data will be read.
 *
 * @param versionScanned The version of the table that is being scanned, if a specific version
 *                       has specifically been requested, e.g. by time travel.
 */
case class PreparedDeltaFileIndex(
    override val spark: SparkSession,
    override val deltaLog: DeltaLog,
    override val path: Path,
    preparedScan: DeltaScan,
    override val partitionSchema: StructType,
    versionScanned: Option[Long])
  extends TahoeFileIndex(spark, deltaLog, path) with DeltaLogging {

  override def tableVersion: Long = preparedScan.version

  /**
   * Returns all matching/valid files by the given `partitionFilters` and `dataFilters`
   */
  override def matchingFiles(
      partitionFilters: Seq[Expression],
      dataFilters: Seq[Expression]): Seq[AddFile] = {
    val actualFilters = ExpressionSet(partitionFilters ++ dataFilters)
    if (preparedScan.allFilters == actualFilters) {
      preparedScan.files.distinct
    } else {
      logInfo(
        s"""
           |Prepared scan does not match actual filters. Reselecting files to query.
           |Prepared: ${preparedScan.allFilters}
           |Actual: ${actualFilters}
         """.stripMargin)
      deltaLog.getSnapshotAt(preparedScan.version).filesForScan(
        projection = Nil, partitionFilters ++ dataFilters).files
    }
  }

  /**
   * Returns the list of files that will be read when scanning this relation. This call may be
   * very expensive for large tables.
   */
  override def inputFiles: Array[String] =
    preparedScan.files.map(f => absolutePath(f.path).toString).toArray

  /** Refresh any cached file listings */
  override def refresh(): Unit = { }

  /** Sum of table file sizes, in bytes */
  override def sizeInBytes: Long =
    preparedScan.scanned.bytesCompressed
      .getOrElse(spark.sessionState.conf.defaultSizeInBytes)

  override def equals(other: Any): Boolean = other match {
    case p: PreparedDeltaFileIndex =>
      p.deltaLog == deltaLog && p.path == path && p.preparedScan == preparedScan &&
        p.partitionSchema == partitionSchema && p.versionScanned == versionScanned
    case _ => false
  }

  override def hashCode(): Int = {
    Objects.hash(deltaLog, path, preparedScan, partitionSchema, versionScanned)
  }

}
