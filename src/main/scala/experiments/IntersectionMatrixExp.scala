package experiments

import java.util.Calendar

import DataStructures.IM
import EntityMatching.PartitionMatching.{ComparisonCentricPrioritization, PartitionMatching, PartitionMatchingFactory}
import EntityMatching.SpaceStatsCounter
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import utils.Constants.MatchingAlgorithm
import utils.Readers.SpatialReader
import utils.{Configuration, ConfigurationParser, Utils}

object IntersectionMatrixExp {
    def main(args: Array[String]): Unit = {
        val startTime = Calendar.getInstance().getTimeInMillis
        Logger.getLogger("org").setLevel(Level.ERROR)
        Logger.getLogger("akka").setLevel(Level.ERROR)
        val log = LogManager.getRootLogger
        log.setLevel(Level.INFO)

        val sparkConf = new SparkConf()
            .setAppName("DS-JedAI")
            .set("spark.serializer", classOf[KryoSerializer].getName)
            .set("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
        val sc = new SparkContext(sparkConf)
        val spark: SparkSession = SparkSession.builder().getOrCreate()

        // Parsing the input arguments
        @scala.annotation.tailrec
        def nextOption(map: OptionMap, list: List[String]): OptionMap = {
            list match {
                case Nil => map
                case ("-c" | "-conf") :: value :: tail =>
                    nextOption(map ++ Map("conf" -> value), tail)
                case ("-f" | "-fraction") :: value :: tail =>
                    nextOption(map ++ Map("fraction" -> value), tail)
                case ("-s" | "-stats") :: tail =>
                    nextOption(map ++ Map("stats" -> "true"), tail)
                case ("-p" | "-partitions") :: value :: tail =>
                    nextOption(map ++ Map("partitions" -> value), tail)
                case _ :: tail =>
                    log.warn("DS-JEDAI: Unrecognized argument")
                    nextOption(map, tail)
            }
        }

        val arglist = args.toList
        type OptionMap = Map[String, String]
        val options = nextOption(Map(), arglist)
        val stats = options.contains("stats")

        val sampleFraction = if (options.contains("fraction")) options("fraction").toDouble else -1d

        if (!options.contains("conf")) {
            log.error("DS-JEDAI: No configuration file!")
            System.exit(1)
        }

        val conf_path = options("conf")
        val conf = ConfigurationParser.parse(conf_path)
        val partitions: Int = if (options.contains("partitions")) options("partitions").toInt else conf.getPartitions

        // setting SpatialReader
        SpatialReader.setPartitions(partitions)
        SpatialReader.noConsecutiveID()
        SpatialReader.setGridType(conf.getGridType)

        // loading source and target, its very important the big dataset to be partitioned first as it will set the partitioner
        val (sourceRDD, targetRDD) = if (conf.partitionBySource){
            val sourceRDD = SpatialReader.load(conf.source.path, conf.source.realIdField, conf.source.geometryField)
                .setName("SourceRDD").persist(StorageLevel.MEMORY_AND_DISK)

            val targetRDD = SpatialReader.load(conf.target.path, conf.target.realIdField, conf.target.geometryField)
                .setName("TargetRDD").persist(StorageLevel.MEMORY_AND_DISK)
            (sourceRDD, targetRDD)
        }
        else {
            val targetRDD = SpatialReader.load(conf.target.path, conf.target.realIdField, conf.target.geometryField)
                .setName("TargetRDD").persist(StorageLevel.MEMORY_AND_DISK)

            val sourceRDD = SpatialReader.load(conf.source.path, conf.source.realIdField, conf.source.geometryField)
                .setName("SourceRDD").persist(StorageLevel.MEMORY_AND_DISK)
            (sourceRDD, targetRDD)
        }

        val distinctSourceMBB = sourceRDD.map(se => (se.originalID, se.mbb)).distinct().map(_._2).setName("distinctSourceMBB").cache()
        val sourceCount = distinctSourceMBB.count().toInt
        log.info("DS-JEDAI: Number of distinct profiles of Source: " + sourceCount + " in " + sourceRDD.getNumPartitions + " partitions")

        val distinctTargetMBB = targetRDD.map(se => (se.originalID, se.mbb)).distinct().map(_._2).setName("distinctTargetMBB").cache()
        val targetCount = distinctTargetMBB.count().toInt
        log.info("DS-JEDAI: Number of distinct profiles of Target: " + targetCount + " in " + targetRDD.getNumPartitions + " partitions")

        Utils(distinctSourceMBB, distinctTargetMBB, sourceCount, targetCount, conf.getTheta)
        val readTime = Calendar.getInstance()
        log.info("DS-JEDAI: Reading input dataset took: " + (readTime.getTimeInMillis - startTime) / 1000.0)

        val matching_startTime = Calendar.getInstance().getTimeInMillis

        val ma = conf.getMatchingAlgorithm
        if (ma == MatchingAlgorithm.SPATIAL) {

            if(stats) SpaceStatsCounter(sourceRDD, targetRDD, conf.getTheta).printSpaceInfo()

            val pm = PartitionMatching(sourceRDD, targetRDD, conf.getTheta)
            val imRDD = if(sampleFraction < 0) pm.getDE9IM else {
                val imRDDtmp = pm.getSampleDE9IM(sampleFraction).setName("IMRDD").cache()
                val ip = imRDDtmp.count()
                imRDDtmp.map{ case(sse, tse) => IM(sse, tse)}
            }
            val de9im_startTime = Calendar.getInstance().getTimeInMillis
            val (totalContains, totalCoveredBy, totalCovers,
                 totalCrosses, totalEquals, totalIntersects,
                 totalOverlaps, totalTouches, totalWithin, intersectingPairs) = imRDD
                .mapPartitions{ imIterator =>
                    var totalContains = 0
                    var totalCoveredBy = 0
                    var totalCovers = 0
                    var totalCrosses = 0
                    var totalEquals = 0
                    var totalIntersects = 0
                    var totalOverlaps = 0
                    var totalTouches = 0
                    var totalWithin = 0
                    var intersectingPairs = 0
                    imIterator.foreach { im =>
                        intersectingPairs += 1
                        if (im.isContains) totalContains += 1
                        if (im.isCoveredBy) totalCoveredBy += 1
                        if (im.isCovers) totalCovers += 1
                        if (im.isCrosses) totalCrosses += 1
                        if (im.isEquals) totalEquals += 1
                        if (im.isIntersects) totalIntersects += 1
                        if (im.isOverlaps) totalOverlaps += 1
                        if (im.isTouches) totalTouches += 1
                        if (im.isWithin) totalWithin += 1
                    }
                    Iterator((totalContains, totalCoveredBy, totalCovers,
                        totalCrosses, totalEquals, totalIntersects,
                        totalOverlaps, totalTouches, totalWithin, intersectingPairs))
                }
                .reduce { case((cnt1, cb1, c1, cs1, eq1, i1, o1, t1,w1, ip1), (cnt2, cb2, c2, cs2, eq2, i2, o2, t2, w2, ip2)) =>
                    (cnt1+cnt2, cb1+cb2, c1+c2, cs1+cs2, eq1+eq2, i1+i2, o1+o2, t1+t2, w1+w2, ip1+ip2)
                }

            val totalRelations = totalContains+totalCoveredBy+totalCovers+totalCrosses+totalEquals+totalIntersects+totalOverlaps+totalTouches+totalWithin
            log.info("DS-JEDAI: Total Intersecting Pairs: " + intersectingPairs)
            log.info("DS-JEDAI: CONTAINS: " + totalContains)
            log.info("DS-JEDAI: COVERED BY: " + totalCoveredBy)
            log.info("DS-JEDAI: COVERS: " + totalCovers)
            log.info("DS-JEDAI: CROSSES: " + totalCrosses)
            log.info("DS-JEDAI: EQUALS: " + totalEquals)
            log.info("DS-JEDAI: INTERSECTS: " + totalIntersects)
            log.info("DS-JEDAI: OVERLAPS: " + totalOverlaps)
            log.info("DS-JEDAI: TOUCHES: " + totalTouches)
            log.info("DS-JEDAI: WITHIN: " + totalWithin)
            log.info("DS-JEDAI: Total Top Relations: " + totalRelations)
            val de9im_endTime = Calendar.getInstance().getTimeInMillis
            log.info("DS-JEDAI: Only DE-9IM Time: " + (de9im_endTime - de9im_startTime) / 1000.0)
        }
        else{
            val budget = conf.getBudget
            //val IMsIter = PartitionMatchingFactory.getProgressiveAlgorithm(conf: Configuration, sourceRDD, targetRDD).getDE9IMIterator
            val IMsIter = ComparisonCentricPrioritization(sourceRDD, targetRDD, conf.getTheta, conf.getWeightingScheme, conf.getBudget).getDE9IMIterator//.getDE9IM.take(conf.getBudget.toInt).toIterator

            var detectedLinks: Long = 0
            var interlinkedGeometries: Long = 0
            var gAUC: Long = 0
            var gAUC5M: Long = 0
            var interlinkedGeometries5M: Long = 0

            var totalContains = 0
            var totalCoveredBy = 0
            var totalCovers = 0
            var totalCrosses = 0
            var totalEquals = 0
            var totalIntersects = 0
            var totalOverlaps = 0
            var totalTouches = 0
            var totalWithin = 0

            IMsIter
                .zipWithIndex
                .foreach { case(im, i) =>
                    if (im.relate) {
                        interlinkedGeometries += 1

                        if (im.isContains) {
                            detectedLinks += 1
                            totalContains += 1
                        }

                        if (im.isCoveredBy) {
                            detectedLinks += 1
                            totalCoveredBy += 1
                        }

                        if (im.isCovers) {
                            detectedLinks += 1
                            totalCovers += 1
                        }

                        if (im.isCrosses) {
                            detectedLinks += 1
                            totalCrosses += 1
                        }

                        if (im.isEquals) {
                            detectedLinks += 1
                            totalEquals += 1
                        }

                        if (im.isIntersects) {
                            detectedLinks += 1
                            totalIntersects += 1
                        }

                        if (im.isOverlaps) {
                            detectedLinks += 1
                            totalOverlaps += 1
                        }

                        if (im.isTouches) {
                            detectedLinks += 1
                            totalTouches += 1
                        }

                        if (im.isWithin) {
                            detectedLinks += 1
                            totalWithin += 1
                        }
                    }
                    gAUC += interlinkedGeometries
                    if (i < 5000000) gAUC5M += interlinkedGeometries
                    if (i == 5000000) interlinkedGeometries5M = interlinkedGeometries
                }
            log.info("DS-JEDAI: Interlinked Geometries: " + interlinkedGeometries)
            log.info("DS-JEDAI: Detected Links: " + interlinkedGeometries)

            log.info("\n")
            log.info("DS-JEDAI: CONTAINS: " + totalContains)
            log.info("DS-JEDAI: COVERED BY: " + totalCoveredBy)
            log.info("DS-JEDAI: COVERS: " + totalCovers)
            log.info("DS-JEDAI: CROSSES: " + totalCrosses)
            log.info("DS-JEDAI: EQUALS: " + totalEquals)
            log.info("DS-JEDAI: INTERSECTS: " + totalIntersects)
            log.info("DS-JEDAI: OVERLAPS: " + totalOverlaps)
            log.info("DS-JEDAI: TOUCHES: " + totalTouches)
            log.info("DS-JEDAI: WITHIN: " + totalWithin + "\n")
            log.info("Geometry AUC: " + gAUC.toDouble / interlinkedGeometries.toDouble / budget.toDouble)
            log.info("Geometry 5M AUC: " + gAUC5M.toDouble / interlinkedGeometries5M.toDouble / 5000000d)

        }

        val matching_endTime = Calendar.getInstance().getTimeInMillis
        log.info("DS-JEDAI: DE-9IM Time: " + (matching_endTime - matching_startTime) / 1000.0)

        val endTime = Calendar.getInstance()
        log.info("DS-JEDAI: Total Execution Time: " + (endTime.getTimeInMillis - startTime) / 1000.0)
    }
}
