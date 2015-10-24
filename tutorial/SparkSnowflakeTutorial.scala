/*
 * Copyright 2015 Databricks
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

package com.snowflakedb.spark.snowflakedb.tutorial
import org.apache.spark.{SparkConf,SparkContext}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.{StructType,StructField,DecimalType,IntegerType,LongType,StringType}
  

/**
 * Source code accompanying the spark-redshift tutorial.
 * The following parameters need to be passed
 * 1. AWS Access Key
 * 2. AWS Secret Access Key
 * 3. Database UserId
 * 4. Database Password
 */
object SparkSnowflakeTutorial {
  /*
   * For Windows Users only
   * 1. Download contents from link 
   *      https://github.com/srccodes/hadoop-common-2.2.0-bin/archive/master.zip
   * 2. Unzip the file in step 1 into your %HADOOP_HOME%/bin. 
   * 3. pass System parameter -Dhadoop.home.dir=%HADOOP_HOME/bin  where %HADOOP_HOME 
   *    must be an absolute not relative path
   */

  def main(args: Array[String]): Unit = {
    // Note: we use 4 args from command line, the rest is hardcoded for now
    if (args.length != 4) {
      println("Needs 4 parameters only passed " + args.length)
      println("parameters needed - $awsAccessKey $awsSecretKey $sfUser $sfPassword")
      System.exit(1)
    }
    val awsAccessKey = args(0)
    val awsSecretKey = args(1)
    val sfUser = args(2)
    val sfPassword = args(3)
    // These are hardcoded for now
    val tempS3Dir = "s3n://sfc-dev1-regression/SPARK-SNOWFLAKE"
    val sfDatabase = "sparkdb"
    val sfSchema = "public"
    val sfWarehouse = "sparkwh"
    val sfAccount = "snowflake"
    val sfURL = "fdb1-gs.dyn.int.snowflakecomputing.com:8080"
    val sfSSL = "off"

    // Prepare Snowflake connection options as a map
    val sfOptions = Map(
      "sfURL" -> sfURL,
      "sfDatabase" -> sfDatabase,
      "sfSchema" -> sfSchema,
      "sfWarehouse" -> sfWarehouse,
      "sfUser" -> sfUser,
      "sfPassword" -> sfPassword,
      "sfAccount" -> sfAccount,
      "sfSSL" -> sfSSL
    )

    val sc = new SparkContext(new SparkConf().setAppName("SparkSQL").setMaster("local"))

    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", awsAccessKey)
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", awsSecretKey)

    val sqlContext = new SQLContext(sc)

    import sqlContext.implicits._

    //Load from a table
    val eventsDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "testdt")
      .load()
    eventsDF.printSchema()
    eventsDF.show()

    val eventsDF2 = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("tempdir", tempS3Dir)
      .option("query", "select * from testdt where i > 2")
      .load()
    eventsDF2.printSchema()
    eventsDF2.show()

    val eventsDF3 = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "testdt")
      .load()
    eventsDF3.filter(eventsDF3("S") > "o'ne").show()
    eventsDF3.filter(eventsDF3("S") > "o'ne\\").show()
    eventsDF3.filter(eventsDF3("T") > "2013-04-05 01:02:03").show()


    /*** ------------------------------------ FINITO
    //Load from a query
    val salesQuery = """SELECT salesid, listid, sellerid, buyerid, 
                               eventid, dateid, qtysold, pricepaid, commission 
                        FROM sales 
                        ORDER BY saletime DESC LIMIT 10000"""
    val salesDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", salesQuery)
      .load()
    salesDF.show()

    val eventQuery = "SELECT * FROM event"
    val eventDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", eventQuery)
      .load()

    /*
     * Register 'event' table as temporary table 'myevent' 
     * so that it can be queried via sqlContext.sql  
     */
    eventsDF.registerTempTable("myevent")

    //Save to a Redshift table from a table registered in Spark

    /*
     * Create a new table redshiftevent after dropping any existing redshiftevent table
     * and write event records with event id less than 1000
     */
    sqlContext.sql("SELECT * FROM myevent WHERE eventid<=1000").withColumnRenamed("eventid", "id")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshiftevent")
      .mode(SaveMode.Overwrite)
      .save()

    /*
     * Append to an existing table redshiftevent if it exists or create a new one if it does not
     * exist and write event records with event id greater than 1000
     */
    sqlContext.sql("SELECT * FROM myevent WHERE eventid>1000").withColumnRenamed("eventid", "id")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshiftevent")
      .mode(SaveMode.Append)
      .save()

    /** Demonstration of interoperability */
    val salesAGGQuery = """SELECT sales.eventid AS id, SUM(qtysold) AS totalqty, SUM(pricepaid) AS salesamt
                           FROM sales
                           GROUP BY (sales.eventid)
                           """
    val salesAGGDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", salesAGGQuery)
      .load()
    salesAGGDF.registerTempTable("salesagg")

    /*
     * Join two DataFrame instances. Each could be sourced from any 
     * compatible Data Source
     */
    val salesAGGDF2 = salesAGGDF.join(eventsDF, salesAGGDF("id") === eventsDF("eventid"))
      .select("id", "eventname", "totalqty", "salesamt")

    salesAGGDF2.registerTempTable("redshift_sales_agg")

    sqlContext.sql("SELECT * FROM redshift_sales_agg")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshift_sales_agg")
      .mode(SaveMode.Overwrite)
      .save()

    }
*** ------------------------------------ FINITO ***/
  }
}