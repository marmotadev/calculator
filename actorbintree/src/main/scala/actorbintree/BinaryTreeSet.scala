/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /**
   * Request with identifier `id` to insert an element `elem` into the tree.
   * The actor at reference `requester` should be notified when this operation
   * is completed.
   */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /**
   * Request with identifier `id` to check whether an element `elem` is present
   * in the tree. The actor at reference `requester` should be notified when
   * this operation is completed.
   */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /**
   * Request with identifier `id` to remove the element `elem` from the tree.
   * The actor at reference `requester` should be notified when this operation
   * is completed.
   */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /**
   * Holds the answer to the Contains request with identifier `id`.
   * `result` is true if and only if the element is present in the tree.
   */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}

class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {

    case Contains(ca, cid, celem) => 
      println(s"Tree  ($self) got request from ($sender), root: $root")
      root ! Contains(ca, cid, celem)
    case ContainsResult(id, res) => 
      println(s"[$id] set($self) got informed with res $res,will tell context.parent")
      context.parent ! ContainsResult(id, res)
    case Insert(ca, cid, celem)   => 
      root ! Insert(ca, cid, celem)
    case GC                       => println("GC requested")
    case _                        => ???
  }

  // optional
  /**
   * Handles messages while garbage collection is performed.
   * `newRoot` is the root of the new binary tree where we want to copy
   * all non-removed elements into.
   */
  def garbageCollecting(newRoot: ActorRef): Receive = ???

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Remove(act, id, elemc) =>
      println(s"Remove($id) $elemc to $act")
      act ! OperationFinished(id)
    case Insert(act, id, elemc) =>
      println(s"Insert($id): got req to insert $elemc to $act")
      println(s"self: $self, target: $act")
      if (removed) {
        println(s"[$id] Current node deleted")
      }
      //      if (self == act) {
      //        if (removed)
      //          println("Changing removed flag")
      //          removed = false
      //        elem = elemc
      //      }
      if (elemc == elem)
        println("Element which inserted equals to currentactor node")
      val c = buildChild(elemc)
      if (elemc < elem) {

        insertChild(c, Left)
        removed = false
      } else if (elemc > elem) {
        insertChild(c, Right)
        removed = false
      } else println("Unknown condition <>")
      act ! OperationFinished(id)
    case Contains(act, id, elemc) =>
      var contains = false
      println(s"Contains($id), $elemc req to $act from $sender")

      //      if (!removed) {
      if (elemc == elem) {
        println(s"Contains: Element itself has $elemc")
        println(s"send contains result to $sender")
        contains = true
        sender ! ContainsResult(id, contains)
      } else {

        if ((subtrees contains Left) || (subtrees contains Right)) {

          var l = subtrees get Left
          l match {
            case Some(a) =>
              println("Contains left")
              a ! Contains(self, id, elemc)
            case _ =>
          }
          l = subtrees get Right
          l match {
            case Some(a) =>
              println("Contains right")
              a ! Contains(self, id, elemc)
            case _ =>
          }

        } else {
          println(s"No children, sender: $sender")
          act ! ContainsResult(id, false)
        }
      }
    //      } else {
    //        println(s"status: Node $elemc remvoved ($id), sender: $act")
    //        println(s"telling does not contain ($id)")
    //        act ! ContainsResult(id, false)
    //      }
    case ContainsResult(id: Int, res: Boolean) =>
      println(s"ContainsResult($id): actor $self got result: $res")
      context.parent ! ContainsResult(id, res)
    case _ => ???
  }

  def buildChild(e: Int): ActorRef = context.actorOf(BinaryTreeNode.props(e, initiallyRemoved = false))

  // optional
  /**
   * `expected` is the set of ActorRefs whose replies we are waiting for,
   * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
   */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = ???

  def hasRight: Boolean = hasNode(Right)

  def hasLeft: Boolean = hasNode(Left)

  def hasNode(pos: Position): Boolean = {
    val l = subtrees get Right
    l match {
      case Some(a) =>
        return true
      case _ =>
        return false
    }
  }
  def insertChild(c: ActorRef, pos: Position) = {
    if (hasNode(pos)) {
      println(s"Node $c already has $pos")
    } else {
      println(s"New node $c at $pos")
      subtrees = subtrees updated (pos, c)
    }
  }

}
