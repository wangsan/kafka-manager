/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package kafka.manager

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}

import akka.pattern._
import akka.util.Timeout
import kafka.manager.utils.zero81.{ReassignPartitionCommand, PreferredReplicaLeaderElectionCommand}
import org.apache.curator.framework.CuratorFramework
import kafka.manager.utils.{AdminUtils, ZkUtils}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Try}

/**
 * @author hiral
 */

import ActorModel._

case class KafkaCommandActorConfig(curator: CuratorFramework,
                                   threadPoolSize: Int = 2,
                                   maxQueueSize: Int = 100,
                                   askTimeoutMillis: Long = 400, 
                                   version: KafkaVersion)
class KafkaCommandActor(kafkaCommandActorConfig: KafkaCommandActorConfig) extends BaseCommandActor {

  private[this] val longRunningExecutor = new ThreadPoolExecutor(
    kafkaCommandActorConfig.threadPoolSize, kafkaCommandActorConfig.threadPoolSize,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue[Runnable](kafkaCommandActorConfig.maxQueueSize))
  private[this] val longRunningExecutionContext = ExecutionContext.fromExecutor(longRunningExecutor)

  private[this] val askTimeout: Timeout = kafkaCommandActorConfig.askTimeoutMillis.milliseconds

  private[this] val adminUtils = new AdminUtils(kafkaCommandActorConfig.version)

  private[this] val reassignPartitionCommand = new ReassignPartitionCommand(adminUtils)

  @scala.throws[Exception](classOf[Exception])
  override def preStart() = {
    log.info("Started actor %s".format(self.path))
  }

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.info("Shutting down long running executor...")
    Try(longRunningExecutor.shutdown())
    super.postStop()
  }

  private[this] def longRunning(fn: => Future[KCCommandResult])(implicit ec: ExecutionContext) : Unit = {
    if(longRunningExecutor.getQueue.remainingCapacity() == 0) {
      sender ! KCCommandResult(Try(throw new UnsupportedOperationException("Long running executor blocking queue is full!")))
    } else {
      fn pipeTo sender
    }
  }


  override def processActorResponse(response: ActorResponse): Unit = {
    response match {
      case any: Any => log.warning("Received unknown message: {}", any)
    }
  }

  override def processCommandRequest(request: CommandRequest): Unit = {
    implicit val ec = longRunningExecutionContext
    request match {
      case KCDeleteTopic(topic) =>
        kafkaCommandActorConfig.version match {
          case Kafka_0_8_1_1 =>
            val result : KCCommandResult = KCCommandResult(Failure(new UnsupportedOperationException(
              s"Delete topic not supported for kafka version ${kafkaCommandActorConfig.version}")))
            sender ! result
          case Kafka_0_8_2_0 =>
            longRunning {
              Future {
                KCCommandResult(Try {
                  adminUtils.deleteTopic(kafkaCommandActorConfig.curator, topic) //this should work in 0.8.2
                  kafkaCommandActorConfig.curator.delete().deletingChildrenIfNeeded().forPath(ZkUtils.getTopicPath(topic))
                })
              }
            }
        }
      case KCCreateTopic(topic, brokers, partitions, replicationFactor, config) =>
        longRunning {
          Future {
            KCCommandResult(Try {
              adminUtils.createTopic(kafkaCommandActorConfig.curator, brokers, topic, partitions, replicationFactor, config)
            })
          }
        }
      case KCPreferredReplicaLeaderElection(topicAndPartition) =>
        longRunning {
          log.info("Running replica leader election : {}", topicAndPartition)
          Future {
            KCCommandResult(
              Try {
                PreferredReplicaLeaderElectionCommand.writePreferredReplicaElectionData(kafkaCommandActorConfig.curator, topicAndPartition)
              }
            )
          }
        }
      case KCReassignPartition(current, generated) =>
        longRunning {
          log.info("Running reassign partition from {} to {}", current, generated)
          Future {
            KCCommandResult(
              reassignPartitionCommand.executeAssignment(kafkaCommandActorConfig.curator, current, generated)
            )
          }
        }
      case any: Any => log.warning("Received unknown message: {}", any)
    }
  }
}

