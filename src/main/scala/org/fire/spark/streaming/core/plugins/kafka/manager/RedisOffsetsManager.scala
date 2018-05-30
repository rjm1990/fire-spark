package org.fire.spark.streaming.core.plugins.kafka.manager


import org.apache.kafka.common.TopicPartition
import org.apache.spark.SparkConf
import org.fire.spark.streaming.core.plugins.redis.RedisConnectionPool._

import scala.collection.JavaConversions._

/**
  * Created by guoning on 2017/10/19.
  *
  * Offset 存储到Redis
  */
private[kafka] class RedisOffsetsManager(val sparkConf: SparkConf) extends OffsetsManager {

  override def getOffsets(groupId: String, topics: Set[String]): Map[TopicPartition, Long] = {
    val offsetMap = safeClose { jedis =>
      topics.flatMap(topic => {
        jedis.hgetAll(generateKey(groupId, topic)).map {
          case (partition, offset) => new TopicPartition(topic, partition.toInt) -> offset.toLong
        }
      })
    }(connect(storeParams))

    // fix bug
    // 如果GroupId 已经在Hbase存在了，这个时候新加一个topic ，则新加的Topic 不会被消费
    val offsetMaps = reset.toLowerCase() match {
      case "largest" => getLatestOffsets(topics.toSeq) ++ offsetMap
      case _ => getEarliestOffsets(topics.toSeq) ++ offsetMap
    }
    logInfo(s"getOffsets [$groupId,${offsetMaps.mkString(",")}] ")
    offsetMaps
  }


  override def updateOffsets(groupId: String, offsetInfos: Map[TopicPartition, Long]): Unit = {
    safeClose { jedis =>
      for ((tp, offset) <- offsetInfos) {
        jedis.hset(generateKey(groupId, tp.topic), tp.partition().toString, offset.toString)
      }
    }(connect(storeParams))
    logInfo(s"updateOffsets [ $groupId,${offsetInfos.mkString(",")} ]")
  }

  override def delOffsets(groupId: String, topics: Set[String]): Unit = {
    safeClose { jedis =>
      for (topic <- topics) {
        jedis.del(generateKey(groupId, topic))
      }
    }(connect(storeParams))
    logInfo(s"delOffsets [ $groupId,${topics.mkString(",")} ]")
  }
}
