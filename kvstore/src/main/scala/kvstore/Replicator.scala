package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.actor.Cancellable

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  
  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case Replicate(key: String, valueOption: Option[String], id: Long) =>
      println(s"Replicate($key, $valueOption, $id)")
      acks += id -> (sender, Replicate(key, valueOption, id))
      replica ! Snapshot(key, valueOption, nextSeq)
      val canc:Cancellable = context.system.scheduler.schedule(Duration.Zero, 10.millis) {
        println("trying scheduled retransmission")
        if (acks.contains(id)) {
          println(s"ack pending, retransmitting $id")
        	replica ! Snapshot(key, valueOption, nextSeq)
          
        }
        else {
          println(s"ack got, canceling retrans")
//          canc.cancel()
        }
      }
    case SnapshotAck(key: String, seq: Long) =>
      println(s"->SnapshotAck($key, $seq)")
    case Some(a) => println(s" Replicator got $a")
    case _ =>
  }

}
