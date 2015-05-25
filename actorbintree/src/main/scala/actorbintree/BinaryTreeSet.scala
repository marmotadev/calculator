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

  private def waitingForContains(origin: ActorRef): Receive = {
    case ContainsResult(id, res) =>
      println(s"ContainsResult (in main loop) result ($id), $res, will forward to $origin")
      context.parent ! ContainsResult(id, res)
      context.unbecome
    //      context become waitingForCheckHealth
  }
  private def waitingForInsert(origin: ActorRef): Receive = {
    case OperationFinished(id) =>
      println(s"OperationFinished(ins): ($id), will forward to $origin")
      context.parent ! OperationFinished(id)
      context.unbecome
    case Insert(a, b, c) => println(s"Unexpected!! $a $b $c")

  }
  private def waitingForRemove(origin: ActorRef): Receive = {
    case OperationFinished(id) =>
      println(s"OperationFinished(remove): ($id), will forward to $origin")
      context.parent ! OperationFinished(id)
      context.unbecome

    case _ => ???
  }

  val normal: Receive = {

    case Contains(ca, cid, celem) =>
      println(s"Contains(Tree)  ($self) got request from ($sender), root: $root")
      root ! Contains(ca, cid, celem)
//      context become waitingForContains(ca)

    case Insert(ca, cid, celem) =>
      println(s"Insert(Tree, $cid): $celem")
      root ! Insert(ca, cid, celem)
//      context become waitingForInsert(ca)
    case Remove(ca, cid, celem) =>
      println(s"Remove(tree) ($cid, $celem)")
      root ! Remove(ca, cid, celem)
//      context become waitingForRemove(ca)
    case GC => println("GC requested")
    //    case ContainsResult (id, res) => 
    case _  => ???
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

  def hasChildren: Boolean = (subtrees contains Left) || (subtrees contains Right)
  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Remove(act, id, elemc) =>
      println(s"Remove($id) $elemc, node: $self, notify: $act")

      if (elemc == elem) {
        println("Remove($id): Element matches, removing")
        removed = true
        act ! OperationFinished(id)
      } else if (hasChildren) {
        if (elemc < elem) {
          val e = subtrees get Left
          e match {
            case Some(ee) =>
              ee ! Remove(act, id, elemc)
            case None =>
              act ! OperationFinished(id)
          }
        } else if (elemc > elem) {
          val e = subtrees get Right
          e match {
            case Some(ee) =>
              ee ! Remove(act, id, elemc)
            case None =>
              act ! OperationFinished(id)
          }
        }
      } else {
        println("No children, will not remove anything")
        act ! OperationFinished(id)
      }

    case Insert(act, id, elemc) =>
      println(s"Insert($id): got req to insert $elemc to $act")
      println(s"self: $self, target: $act")
      if (removed) {
        println(s"Insert($id, $elemc): Current node deleted")
      }

      if (elemc == elem) {
        println("Insert($id) $elemc, $self Element which inserted equals to current actor node")
        println("Updating to remvoed= false")
        removed = false
        act ! OperationFinished(id)
      } else {
        if (elemc < elem) {
          insertChild(act, Left, id, elemc)
          //        removed = false
        } else if (elemc > elem) {
          insertChild(act, Right, id, elemc)
          //        removed = false
        } else println("Unknown condition <>")
      }

    case Contains(act, id, elemc) =>
      var contains = false
      println(s"Contains($id), $elemc req to $act from $sender")

      //      if (!removed) {
      if (elemc == elem) {
        println(s"Contains: Element itself has $elemc")
        contains = !removed
        println(s"ContainsResult($id), res: $contains, val: $elemc: to $sender")
        act ! ContainsResult(id, contains)
      } else {

        if (hasChildren) {

          def sc(l: Option[ActorRef], pos: Position = null) = {
            l match {
              case Some(a) =>
                println(s"Contains $pos: $a")
                println(s"Contains forward to child $a at $pos")
                a ! Contains(act, id, elemc)
              case None => 
                println(s"Contains($id): The last node $pos readched, NOT FOUND")
                act ! ContainsResult(id, false)
            }
          }

          if (elemc < elem)
            sc(subtrees get Left, Left)
          else
            sc(subtrees get Right, Right)

        } else {
          //node has NO children, means we should responde with 
          println(s"ContainsResult($id): No children at node $self, sender: $sender")
          println(s"ContainsResult($id): resp=false to $act")
          act ! ContainsResult(id, false)
        }
      }
    //      } else {
    //        println(s"status: Node $elemc remvoved ($id), sender: $act")
    //        println(s"telling does not contain ($id)")
    //        act ! ContainsResult(id, false)
    //      }
//    case ContainsResult(id: Int, res: Boolean) =>
//      println(s"ContainsResult($id): actor $self got result: $res")
//      println(s"ContainsResult($id) forward to parent: ${context.parent}")
//      act ! ContainsResult(id, res)
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
    subtrees contains pos
  }
  def insertChild(r: ActorRef, pos: Position, id: Integer, elem: Integer) = {
    if (subtrees contains pos) {
      println(s"insertChild: Node $r already has $pos, forward insert to child")
      val cur = subtrees get pos
      cur.get ! Insert(r, id, elem)
    } else {
      val c = buildChild(elem)
      println(s"insertChild: New node $c at $pos in node $self")
      subtrees = subtrees updated (pos, c)
      r ! OperationFinished(id)
    }
  }

}
