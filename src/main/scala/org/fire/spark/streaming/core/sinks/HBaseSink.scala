package org.fire.spark.streaming.core.sinks

import java.util.{ArrayList => JAList}
import java.util.Properties

import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.spark.SparkContext
import org.apache.spark.streaming.Time
import org.apache.hadoop.hbase.client._
import org.apache.spark.rdd.RDD
import org.fire.spark.streaming.core.plugins.hbase.HbaseConnPool

import scala.reflect.ClassTag
import scala.collection.JavaConversions._

/**
  * Created by cloud on 18/4/3.
  */
class HBaseSink[T <: Mutation : ClassTag](@transient override val sc: SparkContext,
                                          val initParams: Map[String, String] = Map.empty[String, String])
  extends Sink[T] {

  override val paramPrefix: String = "spark.sink.hbase."

  private lazy val prop = {
    val p = new Properties()
    p.putAll(param.map { case (k, v) => s"hbase.$k" -> v } ++ initParams)
    p
  }

  private val tableName = prop.getProperty("hbase.table")
  private val commitBatch = prop.getProperty("hbase.commit.batch", "1000").toInt

  private def getConnect: Connection = {
    val conf = HBaseConfiguration.create
    prop.foreach { case (k, v) => conf.set(k, v) }
    HbaseConnPool.connect(conf)
  }

  private def getMutator: BufferedMutator = {
    val connection = getConnect
    val bufferedMutatorParams = new BufferedMutatorParams(TableName.valueOf(tableName))
    connection.getBufferedMutator(bufferedMutatorParams)
  }

  private def getTable: Table = {
    val connection = getConnect
    connection.getTable(TableName.valueOf(tableName))
  }

  /** 输出
    *
    * @param rdd  RDD[Put]或者RDD[Delete]
    * @param time spark.streaming.Time
    */
  override def output(rdd: RDD[T], time: Time = Time(System.currentTimeMillis())): Unit = {
    rdd match {
      case r: RDD[Put] => r.foreachPartition { putRDD =>
        val mutator = getMutator
        putRDD.foreach { p => mutator.mutate(p.asInstanceOf[Put]) }
        mutator.flush()
        mutator.close()
      }

      case r: RDD[Delete] => r.foreachPartition { delRDD =>
        val table = getTable
        val delList = new JAList[Delete]()
        delRDD.foreach { d =>
          delList += d.asInstanceOf[Delete]
          if (delList.size() >= commitBatch) {
            table.batch(delList, null)
            delList.clear()
          }
        }
        if (delList.size() > 0) {
          table.batch(delList, null)
          delList.clear()
        }
        table.close()
      }
    }
  }

  def close(): Unit = HbaseConnPool.close()

}

object HBaseSink {
  def apply(sc: SparkContext) = new HBaseSink[Put](sc)

  def apply[T <: Mutation : ClassTag](rdd: RDD[T]) = new HBaseSink[T](rdd.sparkContext)
}
