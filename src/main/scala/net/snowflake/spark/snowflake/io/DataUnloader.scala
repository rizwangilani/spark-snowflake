/*
 * Copyright 2017 - 2018 Snowflake Computing
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

package net.snowflake.spark.snowflake.io

import java.sql.Connection

import net.snowflake.spark.snowflake._
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import net.snowflake.spark.snowflake.io.SupportedFormat.SupportedFormat
import org.apache.spark.sql.SQLContext
import DefaultJDBCWrapper.DataBaseOperations

/**
  * Created by ema on 3/28/17.
  */
private[io] trait DataUnloader {

  val log: org.slf4j.Logger
  val jdbcWrapper: JDBCWrapper
  val params: MergedParameters
  val sqlContext: SQLContext

  @transient def setup(
                        preStatements: Seq[String] = Seq.empty,
                        sql: String,
                        conn: Connection,
                        keepOpen: Boolean = false,
                        statement: Option[SnowflakeSQLStatement] = None
                      ): Long = {
    try {

      println(statement.map(x=>s"stmt---------->${x.statementString}").getOrElse("stmt--------> null"))
      println(s"sql------------> $sql")

      // Prologue
      val prologueSql = Utils.genPrologueSql(params)
      log.debug(Utils.sanitizeQueryText(prologueSql))
      jdbcWrapper.executeInterruptibly(conn, prologueSql)

      Utils.executePreActions(jdbcWrapper, conn, params, params.table)

      // Run the unload query
      log.debug(Utils.sanitizeQueryText(sql))

      preStatements.foreach { stmt =>
        jdbcWrapper.executeInterruptibly(conn, stmt)
      }
      val res =
        statement
          .map(_.execute(conn))
          .getOrElse(jdbcWrapper.executeQueryInterruptibly(conn, sql))

      // Verify it's the expected format
      val sch = res.getMetaData
      assert(sch.getColumnCount == 3)
      assert(sch.getColumnName(1) == "rows_unloaded")
      assert(sch.getColumnTypeName(1) == "NUMBER") // First record must be in
      val first = res.next()
      assert(first)
      val numRows = res.getLong(1) // There can be no more records
      val second = res.next()
      assert(!second)

      Utils.executePostActions(jdbcWrapper, conn, params, params.table)
      numRows
    } catch {
      case x: Exception => {
        println(x)
        throw x
      }
    }
    finally {
      SnowflakeTelemetry.send(conn.getTelemetry)
      if (!keepOpen) conn.close()
    }
  }

  @transient
  def buildUnloadStmt(
                       query: String,
                       location: String,
                       compression: String,
                       credentialsString: Option[String],
                       format: SupportedFormat = SupportedFormat.CSV
                     ): String = {

    val credentials = credentialsString.getOrElse("")

    // Save the last SELECT so it can be inspected
    Utils.setLastCopyUnload(query)

    /** TODO(etduwx): Refactor this to be a collection of different options, and use a mapper
      * function to individually set each file_format and copy option. */

    val (formatString, queryString): (String, String) = format match {
      case SupportedFormat.CSV =>
        (
          s"""
             |FILE_FORMAT = (
             |    TYPE=CSV
             |    COMPRESSION='$compression'
             |    FIELD_DELIMITER='|'
             |    FIELD_OPTIONALLY_ENCLOSED_BY='"'
             |    NULL_IF= ()
             |  )
             |  """.stripMargin,
          s"""FROM ($query)"""
        )
      case SupportedFormat.JSON =>
        (
          s"""
             |FILE_FORMAT = (
             |    TYPE=JSON
             |    COMPRESSION='$compression'
             |)
             |""".stripMargin,
          s"""FROM (SELECT object_construct(*) FROM ($query))"""
        )

    }

    s"""
       |COPY INTO '$location'
       |$queryString
       |$credentials
       |$formatString
       |MAX_FILE_SIZE = ${params.s3maxfilesize}
       |""".stripMargin.trim


  }

  @transient
  def buildUnloadStatement(
                            statement: SnowflakeSQLStatement,
                            location: String,
                            compression: String,
                            credentialsString: Option[String],
                            format: SupportedFormat = SupportedFormat.CSV
                          ): SnowflakeSQLStatement = {

    val credentials = credentialsString.getOrElse("")

    // Save the last SELECT so it can be inspected
    Utils.setLastCopyUnload(statement.toString)

    val (formatStmt, queryStmt): (SnowflakeSQLStatement, SnowflakeSQLStatement) =
      format match {
        case SupportedFormat.CSV =>
          (
            ConstantString(
              s"""
                 |FILE_FORMAT = (
                 |    TYPE=CSV
                 |    COMPRESSION='$compression'
                 |    FIELD_DELIMITER='|'
                 |    FIELD_OPTIONALLY_ENCLOSED_BY='"'
                 |    NULL_IF= ()
                 |  )
                 |  """.stripMargin
            ) !,
            ConstantString("FROM (") + statement + ")"
          )
        case SupportedFormat.JSON =>
          (
            ConstantString(
              s"""
                 |FILE_FORMAT = (
                 |    TYPE=JSON
                 |    COMPRESSION='$compression'
                 |)
                 |""".stripMargin
            ) !,
            ConstantString("FROM (SELECT object_construct(*) FROM (") + statement + "))"
          )
      }


    ConstantString(s"COPY INTO '$location'") + queryStmt + credentials +
      formatStmt + "MAX_FILE_SIZE = " + params.s3maxfilesize


  }
}
