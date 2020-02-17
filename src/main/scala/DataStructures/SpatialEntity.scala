package DataStructures

import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.io.WKTReader

/**
 * @author George MAndilaras < gmandi@di.uoa.gr > (National and Kapodistrian University of Athens)
 */
case class SpatialEntity( id: Int, originalID: String = "", attributes: Array[KeyValue] = Array(), geometry: Geometry,
                       mbb: MBB, crossesMeridian: Boolean)

object SpatialEntity {

    def apply(id: Int, originalID: String, attributes: Array[KeyValue], wkt: String): SpatialEntity ={
        val wktReader = new WKTReader()
        val geometry: Geometry = wktReader.read(wkt)
        val mbb = MBB(geometry)

        val crossesMeridian =  mbb.crossesMeridian

        SpatialEntity(id, originalID, attributes, geometry, mbb, crossesMeridian)
    }


}
