package com.datasift.dropwizard.kafka.consumer

import com.datasift.dropwizard.kafka.config.{KafkaConfiguration, KafkaClientConfiguration}
import com.yammer.dropwizard.lifecycle.Managed
import com.yammer.dropwizard.Logging
import kafka.consumer.{ConsumerConfig, ConsumerConnector}
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import kafka.serializer.Decoder
import com.yammer.dropwizard.config.Environment
import com.yammer.dropwizard.util.Duration
import java.util.Properties

object Consumer {

  /** create and manage a Kafka Consumer
   *
   * @param conf Service configuration for the consumer.
   * @param env Environment to manage the consumer with.
   * @param f function to process each [[kafka.consumer.KafkaMessageStream]]
   * @tparam A type of the messages being processed, must have an implicit
   *           [[kafka.serializer.Decoder]] in scope
   * @tparam B type of the Service configuration
   * @return a consumer configured to process messages using a ThreadPool
   */
  def apply[A : Decoder, B <: KafkaConfiguration](conf: B, env: Environment)
                                                 (f: MessageStream[A] => Unit): Consumer[A] = {
    apply[A](conf.kafka, env)(f)
  }

  /** create and manage a Kafka Consumer
   *
   * @param conf consumer configuration
   * @param env environment to manage the consumer with
   * @param f function to process each [[kafka.consumer.KafkaMessageStream]]
   * @tparam A type of the messages being processed, must have an implicit
   *           [[kafka.serializer.Decoder]] in scope
   * @return a consumer configured to process messages using a Thread Pool
   */
  def apply[A : Decoder](conf: KafkaClientConfiguration, env: Environment)
                        (f: MessageStream[A] => Unit): Consumer[A] = {
    val executor = env.managedScheduledExecutorService("kafka-consumer-%d", conf.consumer.threads)
    val consumer = new Consumer[A](conf, executor, f)
    env.manage(consumer)
    consumer
  }
}

/** Kafka Consumer run using a fixed ThreadPool
 *
 * When a consuming stream fails due to an uncaught Exception, the stream
 * consumer is restarted after `restartDelay` milliseconds.
 *
 * @param conf KafkaConsumerConfiguration that configures this consumer
 * @param f the function each Thread should run to process a KafkaMessageStream
 */
class Consumer[A : Decoder](conf: KafkaClientConfiguration,
                            executor: ScheduledExecutorService,
                            f: MessageStream[A] => Unit)
  extends Managed with Logging {

  /** underlying consumer */
  lazy val consumer: ConsumerConnector = kafka.consumer.Consumer.create(
    new ConsumerConfig(toProperties(conf)))

  /** start the Consumer */
  def start() {

    for (
      (topic, partitions) <- consumer.streams[A](conf.consumer.partitions);
      stream <- partitions
    ) {
      // submit a consumer task for each stream
      // these run forever, until either they're shutdown or suffer an error
      executor.submit(new Runnable { def run() {
        try {
          f(stream)
        } catch {
          // ignore these, they're not really an error
          case ie: InterruptedException => throw ie
          // everything else is an error, handle it
          case t: Throwable => onError(t, this)
        }
      }})
    }

    log.info("Consumer started for topics {} using {} threads",
      Seq(conf.consumer.partitions.mkString(", "), conf.consumer.threads.toString): _*)
  }

  /** shutdown consumer client connections, commits offsets only if autoCommit enabled */
  def stop() {
    executor.shutdownNow()
    consumer.shutdown()
  }

  /** error handler for a consumer task */
  private def onError(t: Throwable, r: Runnable) {
    conf.consumer.errorPolicy match {
      case ErrorPolicy(Shutdown, delay) if delay == Duration.seconds(0) =>
        log.error(t, "Error processing stream, shutting down consumer")
        stop()
      case ErrorPolicy(Shutdown, delay) =>
        log.error(t, "Error processing stream, shutting down consumer in {}", delay)
        Thread.sleep(delay.toMilliseconds)
        stop()
      case ErrorPolicy(Restart, delay) if (delay == Duration.seconds(0) )=>
        log.error(t, "Error processing stream, restarting thread")
        executor.submit(r)
      case ErrorPolicy(Restart, delay) =>
        log.error(t, "Error processing stream, restarting in {}", delay)
        executor.schedule(r, delay.toNanoseconds, TimeUnit.NANOSECONDS)
    }
  }

  /**Kafka Configuration as a Java Properties object for use by a Consumer */
  private def toProperties(conf: KafkaClientConfiguration): Properties = {
    val props = new Properties
    props.put("zk.connect", conf.zookeeper.quorumSpec)
    props.put("zk.connectiontimeout.ms", Long.box(conf.zookeeper.timeout.toMilliseconds).toString)
    props.put("groupid", conf.consumer.group)
    props.put("socket.timeout.ms", Long.box(conf.socketTimeout.toMilliseconds).toString)
    props.put("socket.buffersize", Long.box(conf.consumer.receiveBufferSize.toBytes).toString)
    props.put("fetch.size", Long.box(conf.consumer.fetchSize.toBytes).toString)
    props.put("backoff.increment.ms", Long.box(conf.consumer.backOffIncrement.toMilliseconds).toString)
    props.put("queuedchunks.max", Long.box(conf.consumer.queuedChunks).toString)
    props.put("autocommit.enable", Boolean.box(conf.consumer.autoCommit).toString)
    props.put("autocommit.interval.ms", Long.box(conf.consumer.autoCommitInterval.toMilliseconds).toString)
    props.put("consumer.timeout.ms", Long.box(conf.consumer.timeout.map(_.toMilliseconds).getOrElse(-1L)).toString)
    props.put("rebalance.retries.max", Long.box(conf.consumer.rebalanceRetries).toString)
    props
  }
}