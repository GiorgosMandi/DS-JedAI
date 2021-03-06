package experiments

import java.util.Calendar

import interlinkers.progressive.ProgressiveAlgorithmsFactory
import model.TileGranularities
import model.entities.{Entity, SpatialEntityType}
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.sedona.core.serde.SedonaKryoRegistrator
import org.apache.sedona.core.spatialRDD.SpatialRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.locationtech.jts.geom.Geometry
import utils.configuration.Constants.ProgressiveAlgorithm.ProgressiveAlgorithm
import utils.configuration.Constants.WeightingFunction.WeightingFunction
import utils.configuration.Constants.{GridType, Relation}
import utils.configuration.{ConfigurationParser, Constants}
import utils.readers.{GridPartitioner, Reader}

object ProgressiveExp {

    def main(args: Array[String]): Unit = {
        Logger.getLogger("org").setLevel(Level.ERROR)
        Logger.getLogger("akka").setLevel(Level.ERROR)
        val log = LogManager.getRootLogger
        log.setLevel(Level.INFO)

        val sparkConf = new SparkConf()
            .setAppName("DS-JedAI")
            .set("spark.serializer", classOf[KryoSerializer].getName)
            .set("spark.kryo.registrator", classOf[SedonaKryoRegistrator].getName)

        val sc = new SparkContext(sparkConf)
        val spark: SparkSession = SparkSession.builder().getOrCreate()

        val parser = new ConfigurationParser()
        val configurationOpt = parser.parse(args) match {
            case Left(errors) =>
                errors.foreach(e => log.error(e.getMessage))
                System.exit(1)
                None
            case Right(configuration) => Some(configuration)
        }
        val conf = configurationOpt.get

        val partitions: Int = conf.getPartitions
        val gridType: GridType.GridType = conf.getGridType
        val budget: Int = conf.getBudget
        val mainWF: WeightingFunction = conf.getMainWF
        val secondaryWF: Option[WeightingFunction] = conf.getSecondaryWF
        val ws: Constants.WeightingScheme = conf.getWS
        val pa: ProgressiveAlgorithm = conf.getProgressiveAlgorithm
        val timeExp: Boolean = conf.measureStatistic
        val relation = conf.getRelation

        log.info(s"DS-JEDAI: Weighting Scheme: ${ws.value}")
        log.info(s"DS-JEDAI: Input Budget: $budget")
        log.info(s"DS-JEDAI: Main Weighting Function: ${mainWF.toString}")
        if (secondaryWF.isDefined) log.info(s"DS-JEDAI: Secondary Weighting Function: ${secondaryWF.get.toString}")
        log.info(s"DS-JEDAI: Progressive Algorithm: ${pa.toString}")

        val startTime = Calendar.getInstance().getTimeInMillis

        // load datasets
        val sourceSpatialRDD: SpatialRDD[Geometry] = Reader.read(conf.source)
        val targetSpatialRDD: SpatialRDD[Geometry] = Reader.read(conf.target)

        // spatial partition
        val partitioner = GridPartitioner(sourceSpatialRDD, partitions, gridType)
        val entityType = SpatialEntityType()
        val sourceRDD: RDD[(Int, Entity)] = partitioner.distributeAndTransform(sourceSpatialRDD, entityType)
        val targetRDD: RDD[(Int, Entity)] = partitioner.distributeAndTransform(targetSpatialRDD, entityType)
        val approximateSourceCount = partitioner.approximateCount
        sourceRDD.persist(StorageLevel.MEMORY_AND_DISK)
        val sourceCount = sourceRDD.count()

        val theta = TileGranularities(sourceRDD.map(_._2.env), approximateSourceCount, conf.getTheta)
        val partitionBorder = partitioner.getPartitionsBorders(Some(theta))
        log.info(s"DS-JEDAI: Source was loaded into ${sourceRDD.getNumPartitions} partitions")

        val matchingStartTime = Calendar.getInstance().getTimeInMillis
        val method = ProgressiveAlgorithmsFactory.get(pa, sourceRDD, targetRDD, theta, partitionBorder,
            partitioner.hashPartitioner, sourceCount, budget, mainWF, secondaryWF, ws)

        if(timeExp){
            //invoke load of target
            targetRDD.count()

            val times = method.time
            val schedulingTime = times._1
            val verificationTime = times._2
            val matchingTime = times._3

            log.info(s"DS-JEDAI: Scheduling time: $schedulingTime")
            log.info(s"DS-JEDAI: Verification time: $verificationTime")
            log.info(s"DS-JEDAI: Interlinking Time: $matchingTime")
        }

        else if (relation.equals(Relation.DE9IM)) {
            val (totalContains, totalCoveredBy, totalCovers, totalCrosses, totalEquals, totalIntersects,
            totalOverlaps, totalTouches, totalWithin, verifications, qp) = method.countAllRelations

            val totalRelations = totalContains + totalCoveredBy + totalCovers + totalCrosses + totalEquals +
                totalIntersects + totalOverlaps + totalTouches + totalWithin
            log.info("DS-JEDAI: Total Verifications: " + verifications)
            log.info("DS-JEDAI: Qualifying Pairs : " + qp)

            log.info("DS-JEDAI: CONTAINS: " + totalContains)
            log.info("DS-JEDAI: COVERED BY: " + totalCoveredBy)
            log.info("DS-JEDAI: COVERS: " + totalCovers)
            log.info("DS-JEDAI: CROSSES: " + totalCrosses)
            log.info("DS-JEDAI: EQUALS: " + totalEquals)
            log.info("DS-JEDAI: INTERSECTS: " + totalIntersects)
            log.info("DS-JEDAI: OVERLAPS: " + totalOverlaps)
            log.info("DS-JEDAI: TOUCHES: " + totalTouches)
            log.info("DS-JEDAI: WITHIN: " + totalWithin)
            log.info("DS-JEDAI: Total Relations Discovered: " + totalRelations)
        }

        else{
            val totalMatches = method.countRelation(relation)
            log.info("DS-JEDAI: " + relation.toString +": " + totalMatches)
        }

        val matchingEndTime = Calendar.getInstance().getTimeInMillis
        log.info("DS-JEDAI: Interlinking Time: " + (matchingEndTime - matchingStartTime) / 1000.0)

        val endTime = Calendar.getInstance().getTimeInMillis
        log.info("DS-JEDAI: Total Execution Time: " + (endTime - startTime) / 1000.0)
    }

}
