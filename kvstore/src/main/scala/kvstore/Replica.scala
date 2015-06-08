package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  override def preStart() {
    arbiter ! Join
  }

  def receive = {
    case JoinedPrimary   => 
      println("Became leader")
      context.become(leader)
    case JoinedSecondary => 
      println("Became replica")
      context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) =>
      kv += (key -> value)
      sender ! OperationAck(id)
    case Remove(key, id) =>
      kv -= key
      sender ! OperationAck(id)
    case Get(key, id) =>
      getVal(key, id, sender)
    case Replicas(replicas: Set[ActorRef]) =>
      println(s"Replicas($replicas)")
    case Some(op) =>
      println(s"-> Operation $op")
    case _                                 => println("Other op2")
  }
  def getVal(key: String, id: Long, s: ActorRef) = {
    if (kv contains key)
      sender ! GetResult(key, Some(kv(key)), id)
    else
      sender ! GetResult(key, None, id)
  }
  val repl = (cur: Long) => {

  }
  var curSeq: Long = -1
  /* TODO Behavior for the replica role. */
  val replica: Receive = {

    case Get(key, id) =>
      getVal(key, id, sender)
    case Snapshot(key: String, valueOption: Option[String], seq: Long) =>
      println(s"->SnapShot($key, $valueOption, $seq)")
      val expected: Long = curSeq + 1
      if (seq > expected) { /*ignored*/ }
      else if (seq <= curSeq) {
        /*ignored, but acknowledged */
        sender ! SnapshotAck(key, seq)
      } else {
        if (valueOption.isDefined)
          kv += key -> valueOption.get
          else 
            kv -= key
          curSeq += 1
        sender ! SnapshotAck(key, seq)
      }
    //TODO make real stuff
    case Some(op) =>
      print(s"replica $op")
    case _ => println("Other op!")
  }

}

