package EntityMatching.PartitionMatching

import DataStructures.SpatialEntity
import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.rdd.RDD
import utils.{Configuration, Constants}

object PartitionMatchingFactory {


    val log: Logger = LogManager.getRootLogger

    def getMatchingAlgorithm(conf: Configuration, source: RDD[SpatialEntity], target: RDD[SpatialEntity], budget: Int,
                             tcount: Long = -1): PartitionMatchingTrait ={

        val targetCount = if (tcount == -1) target.count() else tcount
        val algorithm = conf.getMatchingAlgorithm
        val weightingStrategy = conf.getWeightingScheme
        val theta_msr = conf.getThetaMSR
        algorithm match {
            case Constants.COMPARISON_CENTRIC =>
                log.info("Matching Algorithm: " + Constants.COMPARISON_CENTRIC)
                ComparisonCentricPrioritization(source, target, theta_msr, weightingStrategy)
            case Constants.ΕΝΤΙΤΥ_CENTRIC =>
                log.info("Matching Algorithm: " + Constants.ΕΝΤΙΤΥ_CENTRIC)
                EntityCentricPrioritization(source, target, theta_msr, weightingStrategy, budget, targetCount)
            case Constants.ITERATIVE_ΕΝΤΙΤΥ_CENTRIC =>
                log.info("Matching Algorithm: " + Constants.ITERATIVE_ΕΝΤΙΤΥ_CENTRIC)
                IterativeEntityCentricPrioritization(source, target, theta_msr, weightingStrategy, budget, targetCount)
            case _ =>
                log.info("Matching Algorithm: " + Constants.SPATIAL)
                PartitionMatching(source, target, theta_msr)
        }
    }
}