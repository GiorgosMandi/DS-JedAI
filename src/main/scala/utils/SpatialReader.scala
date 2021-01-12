package utils

import DataStructures.{Entity, MBB, SpatialEntity, SpatioTemporalEntity}
import com.vividsolutions.jts.geom.Geometry
import org.apache.jena.query.ARQ
import net.sansa_stack.rdf.spark.io._
import net.sansa_stack.rdf.spark.model._
import org.apache.jena.riot.Lang
import org.apache.spark.graphx.{EdgeDirection, VertexId, VertexRDD}
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import org.datasyslab.geospark.enums.GridType
import org.datasyslab.geospark.formatMapper.shapefileParser.ShapefileReader
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import org.datasyslab.geospark.spatialPartitioning.SpatialPartitioner
import org.datasyslab.geospark.spatialRDD.SpatialRDD
import org.datasyslab.geosparksql.utils.{Adapter, GeoSparkSQLRegistrator}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.collection.JavaConverters._
import scala.collection.mutable

case class SpatialReader(sourceDc: DatasetConfigurations, partitions: Int, gt: Constants.GridType.GridType = Constants.GridType.QUADTREE) {

    lazy val gridType: GridType = gt match {
        case Constants.GridType.KDBTREE => GridType.KDBTREE
        case _ => GridType.QUADTREE
    }

    lazy val spatialRDD: SpatialRDD[Geometry] = loadSource(sourceDc)

    lazy val spatialPartitioner: SpatialPartitioner = getSpatialPartitioner(spatialRDD)

    lazy val partitioner = new HashPartitioner(spatialPartitioner.numPartitions)

    lazy val partitionsZones: Array[MBB] =
        spatialPartitioner.getGrids.asScala.map(e => MBB(e.getMaxX, e.getMinX, e.getMaxY, e.getMinY)).toArray


    def getSpatialPartitioner(srdd: SpatialRDD[Geometry]): SpatialPartitioner ={
        srdd.analyze()
        if (partitions > 0) srdd.spatialPartitioning(gridType, partitions) else srdd.spatialPartitioning(gridType)
        srdd.getPartitioner
    }


    def loadSource(dc: DatasetConfigurations): SpatialRDD[Geometry] ={
        val extension = dc.path.toString.split("\\.").last
        extension match {
            case "csv" =>
                loadCSV(dc.path, dc.realIdField.getOrElse("id"), dc.geometryField, dc.dateField, header = true )
            case "tsv" =>
                loadTSV(dc.path, dc.realIdField.getOrElse("id"), dc.geometryField, dc.dateField, header = true )
            case "shp" =>
                loadSHP(dc.path, dc.realIdField.getOrElse("id"), dc.geometryField, dc.dateField)
            case "nt" =>
                loadRDF(dc.path, dc.geometryField, dc.dateField, Lang.NTRIPLES)
            case "ttl" =>
                loadRDF(dc.path, dc.geometryField, dc.dateField, Lang.TURTLE)
            case "rdf"|"xml" =>
                loadRDF(dc.path, dc.geometryField, dc.dateField, Lang.RDFXML)
            case "rj" =>
                loadRDF(dc.path, dc.geometryField, dc.dateField, Lang.RDFJSON)
            case _ =>
                null
        }
    }

    def loadCSV(filepath: String, realIdField: String, geometryField: String, dateField: Option[String], header: Boolean):SpatialRDD[Geometry] =
        loadDelimitedFile(filepath, realIdField, geometryField, dateField, ",", header)

    def loadTSV(filepath: String, realIdField: String, geometryField: String, dateField: Option[String], header: Boolean): SpatialRDD[Geometry] =
        loadDelimitedFile(filepath, realIdField, geometryField, dateField, "\t", header)

    /**
     * Loads a delimited file
     * @param filepath path to the delimited text file
     * @param realIdField instances' unique id
     * @param geometryField geometry field
     * @param dateField date field if exists
     * @param delimiter delimiter
     * @param header if first row contains the headers
     * @return a spatial RDD
     */
    def loadDelimitedFile(filepath: String, realIdField: String, geometryField: String, dateField: Option[String], delimiter: String, header: Boolean): SpatialRDD[Geometry] ={
        val conf = new SparkConf()
        conf.set("spark.serializer", classOf[KryoSerializer].getName)
        conf.set("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
        val sc = SparkContext.getOrCreate(conf)
        val spark = SparkSession.getActiveSession.get

        GeoSparkSQLRegistrator.registerAll(spark)

        var inputDF = spark.read.format("csv")
            .option("delimiter", delimiter)
            .option("quote", "\"")
            .option("header", header)
            .load(filepath)
            .filter(col(realIdField).isNotNull)
            .filter(col(geometryField).isNotNull)
            .filter(! col(geometryField).contains("EMPTY"))

        var query = """SELECT ST_GeomFromWKT(GEOMETRIES.""" + geometryField + """) AS WKT,  GEOMETRIES.""" + realIdField + """ AS REAL_ID FROM GEOMETRIES""".stripMargin

        if (dateField.isDefined) {
            inputDF = inputDF.filter(col(dateField.get).isNotNull)
            query =  """SELECT ST_GeomFromWKT(GEOMETRIES.""" + geometryField + """) AS WKT,  GEOMETRIES.""" + realIdField + """ AS REAL_ID, GEOMETRIES.""" + dateField.get + """ AS DATE  FROM GEOMETRIES""".stripMargin
        }

        inputDF.createOrReplaceTempView("GEOMETRIES")

        val spatialDF = spark.sql(query)
        val srdd = new SpatialRDD[Geometry]
        srdd.rawSpatialRDD = Adapter.toRdd(spatialDF)
        srdd
    }

    /**
     * Loads a shapefile
     * @param filepath path to the SHP file
     * @param realIdField instances' unique id
     * @param geometryField geometry field
     * @param dateField date field if exists
     * @return a spatial RDD
     */
    def loadSHP(filepath: String, realIdField: String, geometryField: String, dateField: Option[String]): SpatialRDD[Geometry] ={
        val conf = new SparkConf()
        conf.set("spark.serializer", classOf[KryoSerializer].getName)
        conf.set("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
        val sc = SparkContext.getOrCreate(conf)

        val parentFolder = filepath.substring(0, filepath.lastIndexOf("/"))
        val srdd = ShapefileReader.readToGeometryRDD(sc, parentFolder)
        val idIndex = srdd.fieldNames.indexOf(realIdField)

        // set user data
        val rddWithUserData: RDD[Geometry] = dateField match {
            case Some(dateField) =>
                val dateIndex = srdd.fieldNames.indexOf(dateField)
                srdd.rawSpatialRDD.rdd.map { g =>
                    val userData = g.getUserData.toString.split("\t")
                    val id = userData(idIndex)
                    val date = userData(dateIndex)
                    g.setUserData(id + '\t' + date)
                    g
                }
            case _ =>
                srdd.rawSpatialRDD.rdd.map{ g =>
                    val userData = g.getUserData.toString.split("\t")
                    val id = userData(idIndex)
                    g.setUserData(id)
                    g
                }
        }
        srdd.setRawSpatialRDD(rddWithUserData)

        // filter records with valid geometries and ids
        srdd.setRawSpatialRDD(srdd.rawSpatialRDD.rdd.filter(g => ! (g.isEmpty || g == null || g.getUserData.toString == "")))
        srdd
    }


    /**
     * Loads RDF dataset into Spatial RDD
     * @param filepath path to the RDF file
     * @param geometryPredicate the predicate of the geometry
     * @param datePredicate date predicate if exists
     * @param lang the RDF format (i.e. NTRIPLES, TURTLE, etc.)
     * @return a spatial RDD
     */
    def loadRDF(filepath: String, geometryPredicate: String, datePredicate: Option[String], lang: Lang) : SpatialRDD[Geometry] ={
        val conf = new SparkConf()
        conf.set("spark.serializer", classOf[KryoSerializer].getName)
        conf.set("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
        val sc = SparkContext.getOrCreate(conf)
        val spark = SparkSession.getActiveSession.get

        GeoSparkSQLRegistrator.registerAll(spark)
        ARQ.init()

        val asWKT = "http://www.opengis.net/ont/geosparql#asWKT"

        val allowedPredicates: mutable.Set[String] = mutable.Set(asWKT)

        val cleanGeomPredicate: String = if (geometryPredicate.head == '<' && geometryPredicate.last == '>')
            geometryPredicate.substring(1, geometryPredicate.length-1)
        else geometryPredicate
        allowedPredicates.add(cleanGeomPredicate)

        if(datePredicate.isDefined){
            val datePredicateValue = datePredicate.get
            val cleanDatePredicate: String = if (datePredicateValue.head == '<' && datePredicateValue.last == '>')
                datePredicateValue.substring(1, datePredicateValue.length-1)
            else datePredicateValue
            allowedPredicates.add(cleanDatePredicate)
        }

        val triplesRDD = spark.rdf(lang)(filepath).filter(t => allowedPredicates.contains(t.getPredicate.getURI))

//        val graphRDD = triplesRDD.coalesce(1).asStringGraph()
//        val neighbourRDD: VertexRDD[Array[VertexId]] = graphRDD.collectNeighborIds(EdgeDirection.Out).filter(_._2.length > 0)
//        val reversedNeighbourRDD = neighbourRDD.flatMap(v => v._2.map(n => (n, v._1)))
//        val joined = reversedNeighbourRDD.join(neighbourRDD)
//            .map( v => (v._2._1, v._2._2))
//        val vn = joined.collect()

//        import net.sansa_stack.query.spark.query._
//        triplesRDD.sparql("SSSSSS")
        val rdd = triplesRDD
            .map(t => (t.getSubject.getURI, t.getObject.getLiteral.getLexicalForm))
        val triplesDF = spark.createDataFrame(rdd).toDF("REAL_ID", "WKT")
        triplesDF.createOrReplaceTempView("GEOMETRIES")

        val query = """SELECT ST_GeomFromWKT(GEOMETRIES.WKT),  GEOMETRIES.REAL_ID FROM GEOMETRIES""".stripMargin
        val spatialDF = spark.sql(query)
        val srdd = new SpatialRDD[Geometry]
        srdd.rawSpatialRDD = Adapter.toRdd(spatialDF)
        srdd

    }

    /**
     *  Loads a dataset into Spatial Partitioned RDD. The partitioner
     *  is defined by the first dataset (i.e. the source dataset)
     *
     * @param dc dataset configuration
     * @return a spatial partitioned rdd
     */
    def load(dc: DatasetConfigurations = sourceDc): RDD[(Int, Entity)] = {
        val srdd = if (dc == sourceDc) spatialRDD else loadSource(dc)
        val sp = SparkContext.getOrCreate().broadcast(spatialPartitioner)

        val withTemporal = dc.dateField.isDefined


        val filteredGeometriesRDD = srdd.rawSpatialRDD.rdd
            .map{ geom =>
                val userdata = geom.getUserData.asInstanceOf[String].split("\t")
                (geom, userdata)
            }
            .filter{case (g, _) => !g.isEmpty && g.isValid && g.getGeometryType != "GeometryCollection"}

        val entitiesRDD: RDD[Entity] =
            if(!withTemporal)
                filteredGeometriesRDD.map{ case (geom, userdata) =>  SpatialEntity(userdata(0), geom)}
            else
                filteredGeometriesRDD.mapPartitions{ geomIterator =>
                        val pattern = dc.datePattern.get
                        val formatter = DateTimeFormat.forPattern(pattern)
                        geomIterator.map{
                            case (geom, userdata) =>
                                val realID = userdata(0)
                                val dateStr = userdata(1)
                                val date: DateTime = formatter.parseDateTime(dateStr)
                                SpatioTemporalEntity(realID, geom, date)
                        }
                    }
        entitiesRDD
            .flatMap(se =>  sp.value.placeObject(se.geometry).asScala.map(i => (i._1.toInt, se)))
            .partitionBy(partitioner)
    }

}
