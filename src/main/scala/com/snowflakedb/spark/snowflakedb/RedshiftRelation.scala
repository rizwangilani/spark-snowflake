/*
 * Copyright 2015 TouchType Ltd
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

package com.snowflakedb.spark.snowflakedb

import java.net.URI

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SQLContext}
import org.slf4j.LoggerFactory

import com.snowflakedb.spark.snowflakedb.Parameters.MergedParameters

/**
 * Data Source API implementation for Amazon Redshift database tables
 */
private[snowflakedb] case class RedshiftRelation(
    jdbcWrapper: JDBCWrapper,
    s3ClientFactory: AWSCredentials => AmazonS3Client,
    params: MergedParameters,
    userSchema: Option[StructType])
    (@transient val sqlContext: SQLContext)
  extends BaseRelation
  with PrunedFilteredScan
  with InsertableRelation {

  private val log = LoggerFactory.getLogger(getClass)

  if (sqlContext != null) {
    Utils.assertThatFileSystemIsNotS3BlockFileSystem(
      new URI(params.rootTempDir), sqlContext.sparkContext.hadoopConfiguration)
  }

  override lazy val schema: StructType = {
    userSchema.getOrElse {
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      val conn = jdbcWrapper.getConnector(params)
      try {
        jdbcWrapper.resolveTable(conn, tableNameOrSubquery)
      } finally {
        conn.close()
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val saveMode = if (overwrite) {
      SaveMode.Overwrite
    } else {
      SaveMode.Append
    }
    val writer = new RedshiftWriter(jdbcWrapper, s3ClientFactory)
    writer.saveToRedshift(sqlContext, data, saveMode, params)
  }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    val creds =
      AWSCredentialsUtils.load(params.rootTempDir, sqlContext.sparkContext.hadoopConfiguration)
    Utils.checkThatBucketHasObjectLifecycleConfiguration(params.rootTempDir, s3ClientFactory(creds))
    if (requiredColumns.isEmpty) {
      // In the special case where no columns were requested, issue a `count(*)` against Redshift
      // rather than unloading data.
      val whereClause = FilterPushdown.buildWhereClause(schema, filters)
      val tableNameOrSubquery = params.query.map(q => s"($q)").orElse(params.table).get
      val countQuery = s"SELECT count(*) FROM $tableNameOrSubquery $whereClause"
      log.info(countQuery)
      val conn = jdbcWrapper.getConnector(params)
      try {
        val results = conn.prepareStatement(countQuery).executeQuery()
        if (results.next()) {
          val numRows = results.getLong(1)
          val parallelism = sqlContext.getConf("spark.sql.shuffle.partitions", "200").toInt
          val emptyRow = Row.empty
          sqlContext.sparkContext.parallelize(1L to numRows, parallelism).map(_ => emptyRow)
        } else {
          throw new IllegalStateException("Could not read count from Redshift")
        }
      } finally {
        conn.close()
      }
    } else {
      // Unload data from Redshift into a temporary directory in S3:
      val tempDir = params.createPerQueryTempDir()
      val unloadSql = buildUnloadStmt(requiredColumns, filters, tempDir)
      log.info(unloadSql)
      val conn = jdbcWrapper.getConnector(params)
      try {
        conn.prepareStatement(unloadSql).execute()
      } finally {
        conn.close()
      }
      // Create a DataFrame to read the unloaded data:
      val rdd = sqlContext.sparkContext.newAPIHadoopFile(
        tempDir,
        classOf[RedshiftInputFormat],
        classOf[java.lang.Long],
        classOf[Array[String]])
      val prunedSchema = pruneSchema(schema, requiredColumns)
      rdd.values.mapPartitions { iter =>
        val converter: Array[String] => Row = Conversions.createRowConverter(prunedSchema)
        iter.map(converter)
      }
    }
  }

  private def buildUnloadStmt(
      requiredColumns: Array[String],
      filters: Array[Filter],
      tempDir: String): String = {
    assert(!requiredColumns.isEmpty)
    // Always quote column names:
    val columnList = requiredColumns.map(col => s""""$col"""").mkString(", ")
    val whereClause = FilterPushdown.buildWhereClause(schema, filters)
    val creds = params.temporaryAWSCredentials.getOrElse(
      AWSCredentialsUtils.load(params.rootTempDir, sqlContext.sparkContext.hadoopConfiguration))
    // val credsString: String = AWSCredentialsUtils.getRedshiftCredentialsString(creds)
    // Snowflake-todo: token support
    val awsAccessKey = creds.getAWSAccessKeyId
    val awsSecretKey = creds.getAWSSecretKey
    val query = {
      // Since the query passed to UNLOAD will be enclosed in single quotes, we need to escape
      // any single quotes that appear in the query itself
      val tableNameOrSubquery: String = {
        val unescaped = params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
        unescaped.replace("'", "\\'")
      }
      s"SELECT $columnList FROM $tableNameOrSubquery $whereClause"
    }
    val fixedUrl = Utils.fixS3Url(tempDir)

    // Snowflake-todo Compression support

    s"""
COPY INTO '$fixedUrl'
FROM ($query)
CREDENTIALS = ( AWS_KEY_ID='$awsAccessKey' AWS_SECRET_KEY='$awsSecretKey')
FILE_FORMAT = (
    TYPE=CSV COMPRESSION=none
    FIELD_DELIMITER='|' ESCAPE='\\\\'
    TIMESTAMP_FORMAT='YYYY-MM-DD HH24:MI:SS.FF3'
  )
MAX_FILE_SIZE = 10000000
"""
  }

  private def pruneSchema(schema: StructType, columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }
}