package utils


import DataStructures.SpatialEntity
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{SparkContext, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{Encoder, Encoders, Row, SparkSession}

import scala.reflect.ClassTag

/**
 * @author George Mandilaras < gmandi@di.uoa.gr > (National and Kapodistrian University of Athens)
 */
object Utils {

	val spark: SparkSession = SparkSession.builder().getOrCreate()
	var swapped = false
	var thetaXY: (Double, Double) = _

	/**
	 * Cantor Pairing function. Map two positive integers to a unique integer number.
	 *
	 * @param a Long
	 * @param b Long
	 * @return the unique mapping of the integers
	 */
	def cantorPairing(a: Long, b: Long): Long =  (((a + b) * (a + b + 1))/2) + b

	/**
	 * Bijective cantor pairing. CantorPairing(x, y) == CantorPairing(y, x)
	 *
	 * @param x integer
	 * @param y integer
	 * @return the unique mapping of the integers
	 */
	def bijectivePairing(x: Long, y: Long): Long ={
		if (x < y)
			cantorPairing(y, x)
		else
			cantorPairing(y, x)
	}

	/**
	 * Apply cantor pairing for negative integers
	 *
	 * @param x integer
	 * @param y integer
	 * @return the unique mapping of the integers
	 */
	def signedPairing(x: Long, y: Long): Long ={
		val a = if (x < 0) (-2)*x - 1 else 2*x
		val b = if (y < 0) (-2)*y - 1 else 2*y

		cantorPairing(a, b)
	}

	def inversePairing(z: Long): (Double, Double) ={
		val x = (-1 + math.sqrt(1 + 8 * z))/2
		val floorX = math.floor(x)
		val a = z - (floorX*(1+floorX))/2
		val b = (floorX*(3+floorX)/2) - z
		(a,b)
	}

	/**
	 * Compute the Estimation of the Total Hyper-volume
	 *
	 * @param seRDD Spatial Entities
	 * @return Estimation of the Total Hyper-volume
	 */
	def getETH(seRDD: RDD[SpatialEntity]): Double ={
		getETH(seRDD, seRDD.count())
	}


	/**
	 * Compute the Estimation of the Total Hyper-volume
	 *
	 * @param seRDD Spatial Entities
	 * @param count number of the entities
	 * @return Estimation of the Total Hyper-volume
	 */
	def getETH(seRDD: RDD[SpatialEntity], count: Double): Double ={
		val denom = 1/count
		val coords_sum = seRDD
			.map(se => (se.mbb.maxX - se.mbb.minX, se.mbb.maxY - se.mbb.minY))
			.fold((0, 0)) { case ((x1, y1), (x2, y2)) => (x1 + x2, y1 + y2) }

		val eth = count * ( (denom * coords_sum._1) * (denom * coords_sum._2) )
		eth
	}

	/**
	 * Swaps source to the set with the smallest ETH, and change the relation respectively.
	 *
	 * @param sourceRDD source
	 * @param targetRDD target
	 * @param relation relation
	 * @return the swapped values
	 */
	def swappingStrategy(sourceRDD: RDD[SpatialEntity], targetRDD: RDD[SpatialEntity], relation: String,
						 scount: Long = -1, tcount: Long = -1):	(RDD[SpatialEntity], RDD[SpatialEntity], String)= {

		val sourceCount = if (scount > 0) scount else sourceRDD.count()
		val targetCount = if (tcount > 0) tcount else targetRDD.count()
		val sourceETH = getETH(sourceRDD, sourceCount)
		val targetETH = getETH(targetRDD, targetCount)

		if (targetETH < sourceETH){
			swapped = true
			val newRelation =
				relation match {
					case Constants.WITHIN => Constants.CONTAINS
					case Constants.CONTAINS => Constants.WITHIN
					case Constants.COVERS => Constants.COVEREDBY
					case Constants.COVEREDBY => Constants.COVERS;
					case _ => relation
				}
			(targetRDD, sourceRDD, newRelation)
		}
		else
			(sourceRDD, targetRDD, relation)
	}

	implicit def singleSTR[A](implicit c: ClassTag[String]): Encoder[String] = Encoders.STRING
	implicit def singleInt[A](implicit c: ClassTag[Int]): Encoder[Int] = Encoders.scalaInt
	implicit def tuple[String, Int](implicit e1: Encoder[String], e2: Encoder[Int]): Encoder[(String,Int)] = Encoders.tuple[String,Int](e1, e2)
	def printPartitions(rdd: RDD[Any]): Unit ={
		spark.createDataset(rdd.mapPartitionsWithIndex{ case (i,rows) => Iterator((i,rows.size))}).show(100)
	}

	def toCSV(rdd: RDD[SpatialEntity], path:String) : Unit={
		val schema = StructType(
			StructField("id", IntegerType, nullable = true) ::
			StructField("wkt", StringType, nullable = true)  :: Nil
		)
		val rowRDD: RDD[Row] = rdd.map(s => new GenericRowWithSchema(Array(TaskContext.getPartitionId(), s.geometry.toText), schema))
		val df = spark.createDataFrame(rowRDD, schema)
		df.write.option("header", "true").csv(path)
	}


	/**
	 * initialize theta based on theta measure
	 */
	def initTheta(source:RDD[SpatialEntity], target:RDD[SpatialEntity], thetaMsrSTR: String): (Double, Double) ={
		val thetaMsr: RDD[(Double, Double)] = source
			.union(target)
			.map {
				sp =>
					val env = sp.geometry.getEnvelopeInternal
					(env.getHeight, env.getWidth)
			}
			.setName("thetaMsr")
			.cache()

		var thetaX = 1d
		var thetaY = 1d
		thetaMsrSTR match {
			case Constants.MIN =>
				// filtering because there are cases that the geometries are perpendicular to the axes
				// and have width or height equals to 0.0
				thetaX = thetaMsr.map(_._1).filter(_ != 0.0d).min
				thetaY = thetaMsr.map(_._2).filter(_ != 0.0d).min
			case Constants.MAX =>
				thetaX = thetaMsr.map(_._1).max
				thetaY = thetaMsr.map(_._2).max
			case Constants.AVG =>
				val length = thetaMsr.count
				thetaX = thetaMsr.map(_._1).sum() / length
				thetaY = thetaMsr.map(_._2).sum() / length
			case _ =>
		}
		thetaXY = (thetaX, thetaY)
		thetaXY
	}
}