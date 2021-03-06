package ztis.cassandra

import com.datastax.spark.connector._
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.spark.mllib.recommendation.{MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import ztis.{VideoOrigin, UserAndRating, UserOrigin}

class SparkCassandraClient(val client: CassandraClient, val sparkContext: SparkContext) extends StrictLogging {

  type FeaturesRDD = RDD[(Int, Array[Double])]

  private val columns = SomeColumns("user_id", "user_origin", "video_id", "video_origin", "rating", "timesUpvotedByFriends")

  def userAndRatingsRDD: RDD[UserAndRating] = {
    sparkContext.cassandraTable(client.config.keyspace, client.config.ratingsTableName).map { row =>
      val userId = row.getInt("user_id")
      val userOrigin = UserOrigin.fromString(row.getString("user_origin"))
      val videoID = row.getInt("video_id")
      val videoOrigin = VideoOrigin.fromString(row.getString("video_origin"))
      val rating = row.getInt("rating")
      val timesUpvotedByFriends = row.getInt("timesUpvotedByFriends")

      UserAndRating(userId, userOrigin, videoID, videoOrigin, rating, timesUpvotedByFriends)
    }
  }

  def saveUserAndRatings(rdd: RDD[UserAndRating]): RDD[UserAndRating] = {
    rdd.map(_.toTuple).saveToCassandra(client.config.keyspace, client.config.ratingsTableName, columns)
    rdd
  }

  def saveModel(model: MatrixFactorizationModel): Unit = {
    saveFeatures(model.userFeatures, client.config.keyspace, client.config.userFeaturesTableName)
    saveFeatures(model.productFeatures, client.config.keyspace, client.config.productFeaturesTableName)
  }


  private def saveFeatures(rdd: FeaturesRDD, keyspace: String, table: String): Unit = {
    client.dropTable(keyspace, table)
    //toVector because spark connector does not support automatic mapping of mutable types
    rdd.map(feature => (feature._1, feature._2.toVector)).saveAsCassandraTable(keyspace, table)
  }

  def fetchModel: MatrixFactorizationModel = {
    val userFeatures = loadFeatures(client.config.keyspace, client.config.userFeaturesTableName)
    val productFeatures = loadFeatures(client.config.keyspace, client.config.productFeaturesTableName)

    userFeatures.cache()
    productFeatures.cache()

    val rank = userFeatures.first()._2.length

    new MatrixFactorizationModel(rank, userFeatures, productFeatures)
  }

  private def loadFeatures(keyspace: String, tableName: String): RDD[(Int, Array[Double])] = {
    sparkContext.cassandraTable[(Int, Vector[Double])](keyspace, tableName).map(feature => (feature._1, feature._2.toArray))
  }
}

object SparkCassandraClient {
  def setCassandraConfig(sparkConfig: SparkConf, cassandraConfiguration: CassandraConfiguration): SparkConf = {
    val contactPoint = cassandraConfiguration.contactPoints.get(0)

    sparkConfig.set("spark.cassandra.connection.host", contactPoint.getHostString)
    sparkConfig.set("spark.cassandra.connection.native.port", contactPoint.getPort.toString)

    sparkConfig
  }
}
