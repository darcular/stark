# STARK - **S**patio-**T**emporal Data Analytics on Sp**ark**

STARK is a framework that tightly integrates with [Apache Spark](https://spark.apache.org) and add support for spatial and temporal data types and operations.

## Installation

Using STARK is very simple. Just clone the repository and build the assembly file with
```
sbt assembly
```

The resulting assembly file can be found in `$STARKDIR/target/scala-2.11/stark.jar`. Just add this library to your classpath and STARK is ready to use.

## Usage

STARK uses the `STObject` class as its main data structure. This class can hold your spatio-temporal data. STARK also has [_implicit_ conversion ](http://docs.scala-lang.org/tutorials/tour/implicit-conversions) methods that add spatio-temporal operations to your RDD of `STObject` .

To be able to use the spatio-temporal operations, you have to have an Pair-RDD where the `STObject` is in first position:

```Scala
import dbis.stark._
import dbis.stark.spatial.SpatialRDD._

val countries = sc.textFile("/data/countries") // assume Schema ID;Name;WKT String
    .map(line => line.split(';'))
    .map(arr => (STObject(arr(2)), (arr(0).toInt, arr(1)) ) // ( STObject, (Int, String) )

// find all geometries that contain the given point    
val filtered = countries.contains(STObject("POINT( 50.681898 10.938838 )"))
```

## Features

### Spatial Partitioning
Currently STARK implements two spatial partitioners. The first one is a fixed grid partitioner that applies a grid over the data space where each grid cell corresponds to one partition. The second one is a cost based binary split partitioning where partitions are generated based on the number of contained elements.

When the geometry which the partitioner has to assign to a partition is not a point, we use the center point of that geometry. That means, although a polygon might span over multiple partitons, the assigned partition solely depends on the polygons center point.

The paritioners extend Spark's `Partitioner` interface and thus can be applied using the `repartition` method.

#### Fixed Grid partitioner
The fixed grid partitoner needs at least two parameters. The first one is the RDD to partition and the second one is the number of partitons per dimension (ppD).

```Scala
val countries: RDD[( STObject, (Int, String) )] = ... // see above

val gridPartitioner = new SpatialGridPartitioner(countries, partitionsPerDimension = 10)

val partionedCountries = countries.partitionBy(gridPartitioner)
// further operations
```

In the above example, since the `countries` data uses two dimensional lat/lon coordinates, the `partitionsPerDimension = 10` value results in a grid of 10 x 10 cells, i.e., 100 partitions.

#### Cost based Binary Space Partitioning
The BSP first divides the data space into quadratic cells of defined side length using a fixed grid. Then a histogram of the number of contained data items per cell is created. Along these cells a partition is split into two partitions of equal cost, which is the number of contained points, if the total number of points is greater than a given maximum cost.

The BSP requires at least three parameters: the RDD, the maximum cost, and the side lengths for the quadratic cells.

The advantage of the BSP is that the resulting partitions are of (almost) equal size, i.e., number of contained data items. This achieves an equal workload balance among the executors.
However, partitioning may take longer than fixed grid partitioning.

```Scala
val countries: RDD[( STObject, (Int, String) )] = ... // see above

val bsp = new BSPartitioner(countries, _sideLength = 0.5, _maxCostPerPartition = 1000)

val partionedCountries = countries.partitionBy(bsp)
// further operations
```

### Indexing
To speed up query processing, STARK allows to optionally index the contents of partitions. As such an index structure STARK currently supports the R-tree that comes with [JTS](http://tsusiatsoftware.net/jts/main.html). To support for k nearest neighbor queries (see below) this tree implementeation was extended, based on the [JTSPlus](https://github.com/jiayuasu/JTSplus) implementation.

STARK allows two modes for indexing: *live* and *persistent*. Both modes are transparent to further queries so that filter or join commands do not differ for unindexed use cases.

* **Live Indexing**: The index is built upon execution for each partition, queried according to the current predicate, and then thrown away.
```Scala
val countries: RDD[( STObject, (Int, String) )] = ... // see above
// order is the maximum number of elements in an R-tree node
val result = countries.liveIndex(order = 5).contains(STObject("POINT( 50.681898, 10.938838 )"))
// apply a spatial partitioning before Indexing
val bsp = new BSPartitioner(countries, _sideLength = 0.5, _maxCostPerPartition = 1000)
val result2 = countries.liveIndex(bsp, order = 5).contains(STObject("POINT( 50.681898, 10.938838 )"))
```

* **Persistent Indexing**: Persistent indexing allows to create the index and write the indexed RDD to disk or HDFS. Then this stored index can be loaded again to save the cost for generating it.
Storing can be done with `saveAsObjectFile`:
```Scala
val countries: RDD[( STObject, (Int, String) )] = ... // see above
val bsp = new BSPartitioner(countries, _sideLength = 0.5, _maxCostPerPartition = 1000)
// order is the maximum number of elements in an R-tree node
val countriesIdx = countries.index(bsp, order = 5) // Schema: RDD[RTree[STObject, (STObject, (Int, String))]]
countriesIdx.saveAsObjectFile("/data/countries_idx")
```
The index is loaded using `objectFile`:
```Scala
val countriesIdx = sc.objectFile("/data/countries_idx", 4) // sample no. partitions
val result = countriesIdx.contains(STObject("POINT( 50.681898, 10.938838 )"))
```


### Operators
All operators can be executed on spatial partioned or unpartitioned data. If a spatial partitioning was applied, the operators can omit partitions that cannot, e.g., contain a point based on the spatial bounds of the respective partition.

#### Filter
Supported predicates are:
  * **rdd.intersects(_STObject_)**: Find all entries that intersect with the given object
  * **rdd.contains(_STObject_)**: Find all entries that contain the given object
  * **rdd.containedBy(_STObject_)**: Find all entries that are contained by the given object
  * **rdd.withinDistance(_STObject_, _maxDist_, _distFunc_)**: Find all entries that are within a given maximum distance to the given object according to the given distance function

#### Join
Joins of two spatial RDD are performed using the `join` operator:
```Scala
leftRDD.join(rightRDD, JoinPredicate.CONTAINS)
```

#### Clustering
STARK includes a clustering operator that implements DBSCAN.
```Scala
val clusters  = rdd.dbscan(epsilon = 0.5, minPts = 20, (g,v) => v._1 )
```
Each data item in `rdd` must have an ID (like a primary key) to recognize duplicates. The `dbscan` operator needs a function that extracts this ID from the input tuple. In this case we return the first element of v, the `countries` example this would be the `ID` field.

#### k Nearest Neighbors
```Scala
val qry = STObject("POINT( 50.681898, 10.938838 )")
val neighbors = rdd.kNN(qry, k = 8)
```
