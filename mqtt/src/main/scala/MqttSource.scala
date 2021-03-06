/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.mqtt

import akka.Done
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream._
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttToken
import scala.collection.mutable
import scala.concurrent._
import scala.util.Try
import scala.compat.java8.FutureConverters._

import java.util.concurrent.CompletionStage
import java.util.concurrent.Semaphore

object MqttSource {

  /**
   * Scala API: create an [[MqttSource]] with a provided bufferSize.
   */
  def apply(settings: MqttSourceSettings, bufferSize: Int): Source[MqttMessage, Future[Done]] =
    Source.fromGraph(new MqttSource(settings, bufferSize))

  /**
   * Java API: create an [[MqttSource]] with a provided bufferSize.
   */
  def create(settings: MqttSourceSettings, bufferSize: Int): akka.stream.javadsl.Source[MqttMessage, CompletionStage[Done]] =
    apply(settings, bufferSize).mapMaterializedValue(_.toJava).asJava
}

final class MqttSource(settings: MqttSourceSettings, bufferSize: Int) extends GraphStageWithMaterializedValue[SourceShape[MqttMessage], Future[Done]] {

  import MqttConnectorLogic._

  val out = Outlet[MqttMessage]("MqttSource.out")
  override val shape: SourceShape[MqttMessage] = SourceShape.of(out)
  override protected def initialAttributes: Attributes = Attributes.name("MqttSource")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {

    val subscriptionPromise = Promise[Done]

    (new GraphStageLogic(shape) with MqttConnectorLogic {

      private val queue = mutable.Queue[MqttMessage]()
      private val mqttSubscriptionCallback: Try[IMqttToken] => Unit = conn =>
        subscriptionPromise.complete(conn.map { _ => Done })
      private val backpressure = new Semaphore(bufferSize)

      override val connectionSettings = settings.connectionSettings

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (queue.nonEmpty) {
            pushMessage(queue.dequeue())
          }
        }
      })

      override def handleConnection(client: IMqttAsyncClient) = {
        val (topics, qos) = settings.subscriptions.unzip
        client.subscribe(topics.toArray, qos.map(_.byteValue.toInt).toArray, (), mqttSubscriptionCallback)
      }

      override def beforeHandleMessage(): Unit = {
        backpressure.acquire()
      }

      override def handleMessage(message: MqttMessage): Unit = {
        require(queue.size <= bufferSize)
        if (isAvailable(out)) {
          pushMessage(message)
        } else {
          queue.enqueue(message)
        }
      }

      def pushMessage(message: MqttMessage): Unit = {
        push(out, message)
        backpressure.release()
      }

      override def handleConnectionLost(ex: Throwable) =
        failStage(ex)

    }, subscriptionPromise.future)
  }

}
