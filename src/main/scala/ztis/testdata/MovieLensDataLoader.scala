package ztis.testdata

import java.io.File
import java.net.URL

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.slf4j.StrictLogging
import ztis.cassandra.{CassandraConfiguration, CassandraClient, SparkCassandraClient}
import ztis.{VideoOrigin, Spark, UserAndRating, UserOrigin}

import scala.language.postfixOps
import scala.sys.process._

object MovieLensDataLoader extends App with StrictLogging {

  try {
    val config = ConfigFactory.load("testdata")
    val cassandraConfig = CassandraConfiguration(config)
    val unaryScale = config.getBoolean("testdata.unary-scale")
    val sparkConfig = SparkCassandraClient.setCassandraConfig(Spark.baseConfiguration("MovieLensLoader"), cassandraConfig)
    val sparkCassandraClient = new SparkCassandraClient(new CassandraClient(cassandraConfig), Spark.sparkContext(sparkConfig))

    downloadDataset(config)
    insertMovielensDataToCassandra(sparkCassandraClient, unaryScale)
    logger.info("Data inserted into database")
    sparkCassandraClient.sparkContext.stop()
  } catch {
    case e: Exception => logger.error("Error during loading test data", e)
  }

  private def insertMovielensDataToCassandra(sparkCassandraClient: SparkCassandraClient, unaryScale: Boolean): Unit = {
    val ratingFile = sparkCassandraClient.sparkContext.textFile("ml-1m/ratings.dat")
    val ratings = if (unaryScale) {
      ratingFile.map(toUserAndRating).filter(_.rating > 3).map(_.copy(rating = 1))
    }
    else {
      ratingFile.map(toUserAndRating)
    }

    sparkCassandraClient.saveUserAndRatings(ratings)
  }

  private def toUserAndRating(line: String): UserAndRating = {
    val fields = line.split("::")
    UserAndRating(userID = fields(0).toInt,
      UserOrigin.MovieLens,
      videoID = fields(1).toInt,
      VideoOrigin.MovieLens,
      rating = fields(2).toInt,
      timesUpvotedByFriends = 0)
  }

  private def downloadDataset(config: Config): Unit = {
    val filename = "dataset.zip"

    if (new File(filename).exists()) {
      logger.info(s"File $filename already exists. Skipping downloading...")
      return
    }

    val datasetUrl = config.getString("testdata.url")

    logger.info("Downloading a dataset")
    new URL(datasetUrl) #> new File(filename) !!

    logger.info("Dataset downloaded")

    s"unzip $filename" !!

    logger.info("Dataset unzipped")
  }
}
