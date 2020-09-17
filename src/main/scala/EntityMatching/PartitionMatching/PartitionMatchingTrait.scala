package EntityMatching.PartitionMatching

import DataStructures.{IM, MBB, SpatialEntity, SpatialIndex}
import org.apache.commons.math3.stat.inference.ChiSquareTest
import org.apache.spark.rdd.RDD
import utils.Constants.Relation.Relation
import utils.Constants.WeightStrategy
import utils.Constants.WeightStrategy.WeightStrategy
import utils.Utils
import math.{floor, ceil}


trait PartitionMatchingTrait {

    val orderByWeight: Ordering[(Double, (SpatialEntity, SpatialEntity))] = Ordering.by[(Double, (SpatialEntity, SpatialEntity)), Double](_._1).reverse

    val joinedRDD: RDD[(Int, (Iterable[SpatialEntity], Iterable[SpatialEntity]))]
    val thetaXY: (Double, Double)
    val ws: WeightStrategy

    val partitionsZones: Array[MBB] = Utils.getZones
    val spaceEdges: MBB = Utils.getSpaceEdges

    val totalBlocks: Double = if (ws == WeightStrategy.ECBS || ws == WeightStrategy.PEARSON_X2){
        val globalMinX = joinedRDD.flatMap(p => p._2._1.map(_.mbb.minX)).min()
        val globalMaxX = joinedRDD.flatMap(p => p._2._1.map(_.mbb.maxX)).max()
        val globalMinY = joinedRDD.flatMap(p => p._2._1.map(_.mbb.minY)).min()
        val globalMaxY = joinedRDD.flatMap(p => p._2._1.map(_.mbb.maxY)).max()
        (globalMaxX - globalMinX + 1) * (globalMaxY - globalMinY + 1)
    } else -1

    /**
     * Check if the block is inside the zone of the partition.
     * If the block is on the edges of the partition (hence can belong to many partitions),
     * then it is assigned to the upper-right partition. Also the case of the edges of the
     * space are considered.
     *
     * @param pid    partition's id to get partition's zone
     * @param b coordinates of block
     * @return true if the coords are inside the zone
     */
    def zoneCheck(pid: Int)(b: (Int, Int)): Boolean = {

        // the block is inside its partition
        if (partitionsZones(pid).minX < b._1 && partitionsZones(pid).maxX > b._1 && partitionsZones(pid).minY < b._2 && partitionsZones(pid).maxY > b._2)
            true
        // the block is on the edges of the partitions
        else {
            // we are in the top-right corner - no other partition can possible claiming it
             if (spaceEdges.maxX == b._1 && spaceEdges.maxY == b._2)
               true
            // we are in the right edge of the whole space
            else if (spaceEdges.maxX <= b._1 || spaceEdges.minX >= b._1)
                partitionsZones(pid).minY < b._2+0.001 && partitionsZones(pid).maxY > b._2+0.001
            // we are in the top edge of the whole space
            else if (spaceEdges.maxY <= b._2 || spaceEdges.minY >= b._2)
                partitionsZones(pid).minX < b._1+0.001 && partitionsZones(pid).maxX > b._1+0.001
             // the partition does not touches the edges of space - so we just see if the examined block is in the partition
            else {
                (partitionsZones(pid).minX < b._1+0.01 && partitionsZones(pid).maxX > b._1+0.01) &&
                    (partitionsZones(pid).minY < b._2+0.001 && partitionsZones(pid).maxY > b._2+0.001)
            }
        }
    }

    /**
     * index a list of spatial entities
     *
     * @param entities list of spatial entities
     * @return a SpatialIndex
     */
    def index(entities: Array[SpatialEntity]): SpatialIndex = {
        val spatialIndex = new SpatialIndex()
        entities.zipWithIndex.foreach { case (se, index) =>
            val indices: Array[(Int, Int)] = se.index(thetaXY)
            indices.foreach(i => spatialIndex.insert(i, index))
        }
        spatialIndex
    }

    /**
     * Weight a comparison
     *
     * @param frequency common blocks of e1 and e2
     * @param e1        Spatial entity
     * @param e2        Spatial entity
     * @return weight
     */
    def getWeight(frequency: Int, e1: SpatialEntity, e2: SpatialEntity): Double = {
        ws match {
            case WeightStrategy.ECBS =>
                val e1Blocks = (ceil(e1.mbb.maxX/thetaXY._1).toInt - floor(e1.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e1.mbb.maxY/thetaXY._2).toInt - floor(e1.mbb.minY/thetaXY._2).toInt + 1).toDouble
                val e2Blocks = (ceil(e2.mbb.maxX/thetaXY._1).toInt - floor(e2.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e2.mbb.maxY/thetaXY._2).toInt - floor(e2.mbb.minY/thetaXY._2).toInt + 1).toDouble
                frequency * math.log10(totalBlocks / e1Blocks) * math.log10(totalBlocks / e2Blocks)

            case WeightStrategy.JS =>
                val e1Blocks = (ceil(e1.mbb.maxX/thetaXY._1).toInt - floor(e1.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e1.mbb.maxY/thetaXY._2).toInt - floor(e1.mbb.minY/thetaXY._2).toInt + 1).toDouble
                val e2Blocks = (ceil(e2.mbb.maxX/thetaXY._1).toInt - floor(e2.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e2.mbb.maxY/thetaXY._2).toInt - floor(e2.mbb.minY/thetaXY._2).toInt + 1).toDouble
                frequency / (e1Blocks + e2Blocks - frequency)

            case WeightStrategy.PEARSON_X2 =>
                val e1Blocks = (ceil(e1.mbb.maxX/thetaXY._1).toInt - floor(e1.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e1.mbb.maxY/thetaXY._2).toInt - floor(e1.mbb.minY/thetaXY._2).toInt + 1).toDouble
                val e2Blocks = (ceil(e2.mbb.maxX/thetaXY._1).toInt - floor(e2.mbb.minX/thetaXY._1).toInt + 1) * (ceil(e2.mbb.maxY/thetaXY._2).toInt - floor(e2.mbb.minY/thetaXY._2).toInt + 1).toDouble

                val v1: Array[Long] = Array[Long](frequency, (e2Blocks - frequency).toLong)
                val v2: Array[Long] = Array[Long]((e1Blocks - frequency).toLong, (totalBlocks - (v1(0) + v1(1) + (e1Blocks - frequency))).toLong)

                val chiTest = new ChiSquareTest()
                chiTest.chiSquare(Array(v1, v2))

            case WeightStrategy.CBS | _ =>
                frequency.toDouble
        }
    }

    def apply(relation: Relation): RDD[(String, String)]

    def getDE9IM: RDD[IM]

    def countRelations: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) = {
        getDE9IM
            .mapPartitions { imIterator =>
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
                var interlinkedGeometries = 0
                imIterator.foreach { im =>
                    intersectingPairs += 1
                    if (im.relate) {
                        interlinkedGeometries += 1
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
                }

                Iterator((totalContains, totalCoveredBy, totalCovers,
                    totalCrosses, totalEquals, totalIntersects,
                    totalOverlaps, totalTouches, totalWithin,
                    intersectingPairs, interlinkedGeometries))
            }
            .treeReduce { case ((cnt1, cb1, c1, cs1, eq1, i1, o1, t1, w1, ip1, ig1),
            (cnt2, cb2, c2, cs2, eq2, i2, o2, t2, w2, ip2, ig2)) =>
                (cnt1 + cnt2, cb1 + cb2, c1 + c2, cs1 + cs2, eq1 + eq2, i1 + i2, o1 + o2, t1 + t2, w1 + w2, ip1 + ip2, ig1 + ig2)
            }
    }
}


