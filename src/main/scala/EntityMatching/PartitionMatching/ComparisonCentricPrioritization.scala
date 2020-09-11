package EntityMatching.PartitionMatching


import java.util

import DataStructures.{IM, SpatialEntity}
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import utils.Constants.Relation
import utils.Constants.Relation.Relation
import utils.Constants.ThetaOption.ThetaOption
import utils.Constants.WeightStrategy.WeightStrategy
import utils.Utils
import utils.Readers.SpatialReader

import scala.collection.mutable.ListBuffer


case class ComparisonCentricPrioritization(joinedRDD: RDD[(Int, (Iterable[SpatialEntity], Iterable[SpatialEntity]))],
                                           thetaXY: (Double, Double), ws: WeightStrategy, budget: Long) extends ProgressiveTrait {


    /**
     * First index source and then for each entity of target, find its comparisons from source's index.
     * Weight the comparisons based the weighting scheme and then implement them in a ascending way.
     *
     * The target is most of the matches will be  in the first comparisons.
     *
     * @param relation the examining relation
     * @return  an RDD of pair of IDs and boolean that indicate if the relation holds
     */
    def getComparisons(relation: Relation): RDD[((String, String), Boolean)] ={
        joinedRDD
            .filter(p => p._2._1.nonEmpty && p._2._2.nonEmpty)
            .flatMap { p =>
                val pid = p._1
                val partition = partitionsZones(pid)
                val source = p._2._1.toArray
                val target = p._2._2.toIterator
                val sourceIndex = index(source)
                val frequencies = new Array[Int](source.length)
                val filteringFunction = (b: (Int, Int)) => sourceIndex.contains(b)

                target
                    .map(targetSE => (targetSE, targetSE.index(thetaXY, filteringFunction).map(c => (c, sourceIndex.get(c)))))
                    .filter(_._2.length > 0)
                    .flatMap { case(targetSE: SpatialEntity, sIndices:  Array[((Int, Int), ListBuffer[Int])]) =>

                        util.Arrays.fill(frequencies, 0)
                        sIndices.flatMap(_._2).foreach(i => frequencies(i) += 1)

                        sIndices.flatMap { case (c, indices) =>
                            indices
                                .map(i => (source(i), frequencies(i)))
                                .filter { case (e1, _) => e1.referencePointFiltering(targetSE, c, thetaXY, Some(partition)) && e1.testMBB(targetSE, relation) }
                                .map { case (e1, f) => (getWeight(f, e1, targetSE), (e1, targetSE)) }
                        }
                    }
            }
            .sortByKey(ascending = false)
            .map(_._2)
            .map(c => ((c._1.originalID, c._2.originalID), c._1.relate(c._2, relation)))
    }

    def getDE9IM: RDD[IM] =
        joinedRDD
            .filter(p => p._2._1.nonEmpty && p._2._2.nonEmpty)
            .flatMap { p =>
                val pid = p._1
                val partition = partitionsZones(pid)
                val source = p._2._1.toArray
                val target = p._2._2.toIterator
                val sourceIndex = index(source)
                val filteringFunction = (b: (Int, Int)) => sourceIndex.contains(b)
                val frequencies = new Array[Int](source.length)

                target
                    .map(targetSE => (targetSE, targetSE.index(thetaXY, filteringFunction).map(c => (c, sourceIndex.get(c)))))
                    .filter(_._2.length > 0)
                    .flatMap { case(targetSE: SpatialEntity, sIndices:  Array[((Int, Int), ListBuffer[Int])]) =>

                        util.Arrays.fill(frequencies, 0)
                        sIndices.flatMap(_._2).foreach(i => frequencies(i) += 1)
                        sIndices.flatMap { case (c, indices) =>
                            indices.map(i => (source(i), frequencies(i)))
                                .filter { case (e1, _) => e1.referencePointFiltering(targetSE, c, thetaXY, Some(partition)) && e1.testMBB(targetSE, Relation.INTERSECTS, Relation.TOUCHES)}
                                .map { case (e1, f) => (getWeight(f, e1, targetSE), (e1, targetSE)) }
                        }
                    }
            }
            .map{case (w, c) => (w, IM(c._1, c._2))}
            .sortByKey(ascending = false)
            .map(_._2)

    def getDE9IMIterator: Iterator[IM] ={
        joinedRDD
            .filter(p => p._2._1.nonEmpty && p._2._2.nonEmpty)
            .flatMap { p =>
                val pid = p._1
                val partition = partitionsZones(pid)
                val source = p._2._1.toArray
                val target = p._2._2.toIterator
                val sourceIndex = index(source)
                val filteringFunction = (b: (Int, Int)) => sourceIndex.contains(b)
                val frequencies = new Array[Int](source.length)

                target
                    .map(targetSE => (targetSE, targetSE.index(thetaXY, filteringFunction).map(c => (c, sourceIndex.get(c)))))
                    .filter(_._2.length > 0)
                    .flatMap { case(targetSE: SpatialEntity, sIndices:  Array[((Int, Int), ListBuffer[Int])]) =>

                        util.Arrays.fill(frequencies, 0)
                        sIndices.flatMap(_._2).foreach(i => frequencies(i) += 1)
                        sIndices.flatMap { case (c, indices) =>
                            indices.map(i => (source(i), frequencies(i)))
                                .filter { case (e1, _) => e1.referencePointFiltering(targetSE, c, thetaXY, Some(partition)) && e1.testMBB(targetSE, Relation.INTERSECTS, Relation.TOUCHES)}
                                .map { case (e1, f) => (getWeight(f, e1, targetSE), (e1, targetSE)) }
                        }
                    }
            }
            .map{case (w, c) => (w, IM(c._1, c._2))}
            .takeOrdered(budget.toInt)(Ordering.by[(Double, IM), Double](_._1).reverse)
            .toIterator
            .map(_._2)
    }

}



/**
 * auxiliary constructor
 */
object ComparisonCentricPrioritization {

    def apply(source:RDD[SpatialEntity], target:RDD[SpatialEntity], thetaOption: ThetaOption,
              ws: WeightStrategy, budget: Long): ComparisonCentricPrioritization ={
        val thetaXY = Utils.getTheta
        val sourcePartitions = source.map(se => (TaskContext.getPartitionId(), se))
        val targetPartitions = target.map(se => (TaskContext.getPartitionId(), se))

        val joinedRDD = sourcePartitions.cogroup(targetPartitions, SpatialReader.spatialPartitioner)
        ComparisonCentricPrioritization(joinedRDD, thetaXY, ws, budget)
    }

}
