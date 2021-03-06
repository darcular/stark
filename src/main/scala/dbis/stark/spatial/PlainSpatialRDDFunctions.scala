package dbis.stark.spatial

import java.nio.file.Paths

import dbis.stark.dbscan.{ClusterLabel, DBScan}
import dbis.stark.spatial.JoinPredicate.JoinPredicate
import dbis.stark.spatial.indexed.RTree
import dbis.stark.spatial.indexed.live.LiveIndexedSpatialRDDFunctions
import dbis.stark.spatial.partitioner.{SpatialGridPartitioner, SpatialPartitioner}
import dbis.stark.{Distance, STObject}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.rdd.RDD

import scala.collection.mutable
import scala.reflect.ClassTag


/**
 * A helper class used in the implicit conversions
 *
 * @param rdd The original RDD to treat as a spatial RDD
 */
class PlainSpatialRDDFunctions[G <: STObject : ClassTag, V: ClassTag](
    rdd: RDD[(G,V)]
  ) extends SpatialRDDFunctions[G,V] with Serializable {


  def saveAsStarkTextFile(path: String): Unit = rdd.partitioner.foreach {
    case sp: SpatialPartitioner =>
      val wkts = rdd.partitions.indices.map{i =>
        Array(sp.partitionExtent(i).wkt,"","","part-%05d".format(i)).mkString(STSparkContext.PARTITIONINFO_DELIM)
      }

      rdd.saveAsTextFile(path)
      rdd.sparkContext.parallelize(wkts).saveAsTextFile(Paths.get(path,STSparkContext.PARTITIONINFO_FILE).toString)

    // in case there is no or not a spatial partitioner, use normal save
    case _ => rdd.saveAsTextFile(path)
  }



  /**
   * Find all elements that intersect with a given query geometry
   */
  def intersects(qry: G) = new SpatialFilterRDD[G,V](rdd, qry, JoinPredicate.INTERSECTS)
    
  /**
   * Find all elements that are contained by a given query geometry
   */
  def containedby(qry: G) = new SpatialFilterRDD[G,V](rdd, qry, JoinPredicate.CONTAINEDBY)

  /**
   * Find all elements that contain a given other geometry
   */
  def contains(o: G) = new SpatialFilterRDD[G,V](rdd, o, JoinPredicate.CONTAINS)

  def withinDistance(qry: G, maxDist: Distance, distFunc: (STObject,STObject) => Distance) =
    new SpatialFilterRDD(rdd, qry, PredicatesFunctions.withinDistance(maxDist, distFunc) _)

      
  def kNN(qry: G, k: Int, distFunc: (STObject, STObject) => Distance): RDD[(G,(Distance,V))] = {
//    // compute k NN for each partition individually --> n * k results
//    val r = rdd.mapPartitions({iter => iter.map { case (g,v) =>
//        val d = distFunc(g,qry)
//        (g,(d,v)) // compute and return distance
//      }
//      .toList
//      .sortWith(_._2._1 < _._2._1) // on distance
//      .take(k) // take only the fist k
//      .toIterator // remove the iterator
//    })
//
//    // sort all n lists and sort by distance, then take only the first k elements
//    val arr = r.sortBy(_._2._1, ascending = true).take(k)
//
//    // return as an RDD
//    rdd.sparkContext.parallelize(arr)

    val knn = rdd.map{ case(g,v) => (distFunc(qry,g), (g,v)) } // compute distances and make it key
                  .sortByKey(ascending = true) // sort by distance
                  .take(k) // take only the first k elements
                  .map{ case (d,(g,v)) => (g, (d,v))}  // project to desired format

    rdd.sparkContext.parallelize(knn) // return as RDD
  }   
  
  
  /**
   * Join this SpatialRDD with another (spatial) RDD.<br><br>
   * <b>NOTE:</b> There will be no partition pruning and basically all cartesian combinations have to be checked
   *
   * @param other The other RDD to join with.
   * @param pred The join predicate as a function
   * @return Returns a RDD with the joined values
   */
  def join[V2 : ClassTag](other: RDD[(G, V2)], pred: (G,G) => Boolean) =
    new SpatialJoinRDD(rdd, other, pred)

  def join[V2 : ClassTag](other: RDD[(G, V2)], pred: JoinPredicate, partitioner: Option[SpatialPartitioner] = None) =      
    new SpatialJoinRDD(
        if(partitioner.isDefined) rdd.partitionBy(partitioner.get) else rdd , 
        if(partitioner.isDefined) other.partitionBy(partitioner.get) else other, 
        pred)
      

  /**
   * Cluster this SpatialRDD using DBSCAN
   *
   * @param minPts MinPts parameter to DBSCAN
   * @param epsilon Epsilon parameter to DBSCAN
   * @param keyExtractor A function that extracts or generates a unique key for each point
   * @param includeNoise A flag whether or not to include noise points in the result
   * @param maxPartitionCost Maximum cost (= number of points) per partition
   * @param outfile An optional filename to write clustering result to
   * @return Returns an RDD which contains the corresponding cluster ID for each tuple
   */
  def cluster[KeyType](
		  minPts: Int,
		  epsilon: Double,
		  keyExtractor: ((G,V)) => KeyType,
		  includeNoise: Boolean = true,
		  maxPartitionCost: Int = 10,
		  outfile: Option[String] = None
		  ) = {
				  
	  // create a dbscan object with given parameters
	  val dbscan = new DBScan[KeyType,(G,V)](epsilon, minPts)
	  dbscan.maxPartitionSize = maxPartitionCost
			  
	  // DBScan expects Vectors -> transform the geometry into a vector
	  val r = rdd.map{ case (g,v) =>
  	  /* TODO: can we make this work for polygons as well?
		   * See Generalized DBSCAN:
		   * Sander, Ester, Krieger, Xu
		   * "Density-Based Clustering in Spatial Databases: The Algorithm GDBSCAN and Its Applications"
		   * http://link.springer.com/article/10.1023%2FA%3A1009745219419
		   */
		  val c = g.getCentroid
		  
		  
		  /* extract the key for the cluster points
		   * this is used to distinguish points that have the same coordinates
		   */
		  val key = keyExtractor((g,v))
		  
		  /* emit the tuple as input for the clustering
		   *  (id, coordinates, payload)
		   * The payload is the actual tuple to which we will project at the end
		   * to hide the internals of this implementation and to return data that
		   * matches the input format and
		   */
		  
		  (key, Vectors.dense(c.getY, c.getX), (g,v))
	  }
	  
	  // start the DBScan computation
	  val model = dbscan.run(r)
			  
	  /*
	   * Finally, transform into a form that corresponds to a spatial RDD
	   * (Geo, (ClusterID, V)) - where V is the rest of the tuple, i.e. its
	   * actual content.
	   *
	   * Also, remove noise if desired.
	   * TODO: If noise has to be removed, can we use this more deeply inside
	   * the DBSCAN code to reduce data?
	   *
	   * We do know that there is a payload, hence calling .get is safe
	   */
    val points = if (includeNoise) model.points else model.points.filter(_.label != ClusterLabel.Noise)

    /* if the outfile is defined, write the clustering result
     * this can be used for debugging and visualization
     */
    if(outfile.isDefined)
    points.coalesce(1).saveAsTextFile(outfile.get)

    points.map { p => (p.payload.get._1, (p.clusterId, p.payload.get._2)) }
  }

  override def skyline(
      ref: STObject,
      distFunc: (STObject, STObject) => (Distance, Distance),
      dominates: (STObject, STObject) => Boolean,
      ppD: Int,
      allowCache: Boolean = false): RDD[(G,V)] = {

    def localSkyline(iter: Iterator[(STObject,(G,V))]): Iterator[(STObject,(G,V))] = {

      val s = new Skyline[(G,V)](dominates = dominates)

      iter.foreach(tuple => s.insert(tuple))
      s.iterator
    }

    /*
     * compute the distance in space and time for each element
     * The STObject in first position represents the distances
     */
    val distanceRDD = rdd.map{ case (g,v) =>
      val (sDist, tDist) = distFunc(ref, g)

      (STObject(sDist.minValue, tDist.minValue), (g,v))
    }

    // TODO: specify parititoner as parameter - but it has to work on distance RDD...
    val partitioner = new SpatialGridPartitioner(distanceRDD, ppD, withExtent = false)
    val partedDistRDD = distanceRDD.partitionBy(partitioner)

    val cachedDistRDD = if(allowCache) partedDistRDD.cache() else partedDistRDD

    val globalBS = cachedDistRDD.mapPartitionsWithIndex{ case (idx, iter) =>
      val bs = mutable.BitSet.empty
      if(iter.nonEmpty)
        bs += idx

      Iterator(bs)
    }.reduce{ case (bs1, bs2) => bs1 | bs2}

    for(idx <- globalBS) {
      val currMax = partitioner.partitionExtent(idx).ur.c
      val currMaxTup = STObject(currMax(0), currMax(1))

      // TODO: here we can be more selective by computing which partitions are dominated
      // based on the partiton index
      (idx until partitioner.numPartitions).foreach{ otherPart =>
        val otherMin = partitioner.partitionExtent(otherPart).ll.c
        val otherMinTup = STObject(otherMin(0), otherMin(1))

        if(Skyline.centroidDominates(currMaxTup, otherMinTup)) {
          globalBS(otherPart) = false
        }
      }
    }

    val bsBC = rdd.sparkContext.broadcast(globalBS)

    cachedDistRDD.mapPartitionsWithIndex{ case (idx, iter) =>
        if(bsBC.value(idx))
          localSkyline(iter)
        else
          Iterator.empty
      } // compute local skyline in each partition
      .coalesce(1, shuffle = true)                // collect local skyline points into a single partition
      .mapPartitions(localSkyline) // compute skyline out of the local skyline points
      .map(_._2)                  // transform back to input format

  }

  override def skylineAgg(ref: STObject,
                          distFunc: (STObject, STObject) => (Distance, Distance),
                          dominatesRel: (STObject, STObject) => Boolean
                ): RDD[(G,V)] = {

    def combine(sky: Skyline[(G,V)], tuple: (G,V)): Skyline[(G,V)] = {
      val dist = distFunc(tuple._1, ref)
      val distObj = STObject(dist._1.minValue, dist._2.minValue)
      sky.insert((distObj, tuple))
      sky
    }

    def merge(sky1: Skyline[(G,V)], sky2: Skyline[(G,V)]): Skyline[(G,V)] = {
      sky1.merge(sky2)
    }


    val startSkyline = new Skyline[(G,V)](dominates = dominatesRel)
    val skyline = rdd.aggregate(startSkyline)(combine,merge)

    rdd.sparkContext.parallelize(skyline.skylinePoints.map(_._2))
  }

  // LIVE

  def liveIndex(order: Int): LiveIndexedSpatialRDDFunctions[G,V] = liveIndex(None, order)

  def liveIndex(partitioner: SpatialPartitioner, order: Int): LiveIndexedSpatialRDDFunctions[G,V] =
    liveIndex(Some(partitioner), order)

  def liveIndex(partitioner: Option[SpatialPartitioner], order: Int) = {
    val reparted = if(partitioner.isDefined) rdd.partitionBy(partitioner.get) else rdd
    new LiveIndexedSpatialRDDFunctions(reparted, order)
  }

  def index(partitioner: SpatialPartitioner, order: Int): RDD[RTree[G,(G,V)]] = index(Some(partitioner), order)

  /**
    * Create an index for each partition.
    *
    * This puts all data items of a partition into an Index structure, e.g., R-tree
    * and thus changes the type of the RDD from RDD[(STObject, V)] to RDD[RTree[STObject, (STObject, V)]]
    */
  def index(partitioner: Option[SpatialPartitioner] = None, order: Int = 10): RDD[RTree[G,(G,V)]] = {
    val reparted = if(partitioner.isDefined) rdd.partitionBy(partitioner.get) else rdd
    reparted.mapPartitions(iter => {
      val tree = new RTree[G, (G, V)](order)

      for ((g, v) <- iter) {
        tree.insert(g, (g, v))
      }

      Iterator.single(tree)
    },
    preservesPartitioning = true) // preserve partitioning
  }

}
