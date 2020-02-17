package DataStructures

import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import utils.Constants

/**
 * @author George MAndilaras < gmandi@di.uoa.gr > (National and Kapodistrian University of Athens)
 */
case class MBB(maxX:Double, minX:Double, maxY:Double, minY:Double){

    def getGeometry: Geometry ={
        val coordsList: List[(Double, Double)] = List((minX, minY), (minX, maxY), (maxX, maxY), (maxX, minY), (minX, minY))
        val coordsAr: Array[Coordinate] = coordsList.map(c => new Coordinate(c._1, c._2)).toArray
        val gf: GeometryFactory = new GeometryFactory()
        gf.createPolygon(coordsAr)
    }

    def crossesMeridian: Boolean ={
        (minX < Constants.MAX_LONG && maxX > Constants.MAX_LONG) || (minX < Constants.MIN_LONG && maxX > Constants.MIN_LONG)
    }


    def splitOnMeridian: (MBB, MBB) ={
        if (minX < Constants.MIN_LONG && maxX > Constants.MIN_LONG){
            val easternMBB: MBB = MBB(minX, Constants.MIN_LONG, maxY, minY)
            val westernMBB: MBB = MBB(Constants.MIN_LONG, maxX, maxY, minY)

            (westernMBB, easternMBB)
        }
        else if (minX < Constants.MAX_LONG && maxX >Constants.MAX_LONG) {
            val easternMBB: MBB = MBB(maxX, Constants.MAX_LONG, maxY, minY)
            val westernMBB: MBB = MBB(Constants.MAX_LONG, minX, maxY, minY)

            (westernMBB, easternMBB)
        }
        else (null, null)
    }
}

object  MBB {
    def apply(geom: Geometry): MBB ={
        val env = geom.getEnvelopeInternal
        MBB(env.getMaxX, env.getMinX, env.getMaxY, env.getMinY)
    }
}
