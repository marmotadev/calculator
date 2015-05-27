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

  //  private def waitingForContains(origin: ActorRef): Receive = {
  //    case ContainsResult(id, res) =>
  //      println(s"ContainsResult (in main loop) result ($id), $res, will forward to $origin")
  //      context.parent ! ContainsResult(id, res)
  //      context.unbecome
  //    //      context become waitingForCheckHealth
  //  }
  //  private def waitingForInsert(origin: ActorRef): Receive = {
  //    case OperationFinished(id) =>
  //      println(s"OperationFinished(ins): ($id), will forward to $origin")
  //      context.parent ! OperationFinished(id)
  //      context.unbecome
  //    case Insert(a, b, c) => println(s"Unexpected!! $a $b $c")
  //
  //  }
  //  private def waitingForRemove(origin: ActorRef): Receive = {
  //    case OperationFinished(id) =>
  //      println(s"OperationFinished(remove): ($id), will forward to $origin")
  //      context.parent ! OperationFinished(id)
  //      context.unbecome
  //
  //    case _ => ???
  //  }

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
    case GC =>
      println("GC requested")
      val newRoot = createRoot
      println(s"NEW ROOT: $newRoot")
      context become garbageCollecting(newRoot)
      root ! CopyTo(newRoot)
    //    case ContainsResult (id, res) => 

    case Some(cmd) =>
      println(s"Set recieved unknown command: $cmd")
      ???
  }

  // optional
  /**
   * Handles messages while garbage collection is performed.
   * `newRoot` is the root of the new binary tree where we want to copy
   * all non-removed elements into.
   */
  def garbageCollecting(newRoot: ActorRef): Receive = {

    case Contains(ca, cid, celem) =>
      println(s"GC:Contains(Tree)  ($self) got request from ($sender), root: $root")
      pendingQueue = pendingQueue enqueue Contains(ca, cid, celem)

    case Insert(ca, cid, celem) =>
      println(s"GC:Insert(Tree, $cid): $celem")
      pendingQueue = pendingQueue enqueue Insert(ca, cid, celem)
    case Remove(ca, cid, celem) =>
      println(s"GC:Remove(tree) ($cid, $celem)")
      pendingQueue = pendingQueue enqueue Remove(ca, cid, celem)
    case GC =>
      println(s"GC: Ignored")

    case CopyFinished =>
      println(s"GC finished!!!")
      println(s"CopyFinished: $sender reports finished, will replay message queue: $pendingQueue")
      println("SWITCHING ROOTS")
      root = newRoot
      context.unbecome
      for (op <- pendingQueue.seq) {
        newRoot ! op
      }

    case _ => ???
  }

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
      println(s"Insert($id): got req to insert $elemc to $self, notify: $act")
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

    case CopyTo(treeNode: ActorRef) =>

      //treeNode 
      println(s"CopyTo: $self ($elem)-> $treeNode")
      var s: Set[ActorRef] = Set()
      if (hasLeft) {
        s = s + (subtrees get Left).get
      }
      if (hasRight) {
        println("Copy child right")
        (subtrees get Right).get ! CopyTo(treeNode)
        s = s + (subtrees get Right).get
      }

      if (removed) {
        println(s"CopyTo: Node $self removed, not coyping, will check children")
        self ! OperationFinished(0)
      } else {
        s = s + self
        treeNode ! Insert(self, 0, elem) // TODO

      }
      
      println(s"SET: $s, entering copyying mode (false)")
      context become copying(s, false)

      if (hasLeft) {
        println("Copy child left")
        (subtrees get Left).get ! CopyTo(treeNode)
      }
      if (hasRight) {
        println("Copy child right")
        (subtrees get Right).get ! CopyTo(treeNode)
      }

    case CopyFinished =>
      println(s"CopyFinished: signaled to $self from $sender  finished from $self, will notify ${context.parent}")
      self ! PoisonPill
    //      context.parent ! CopyFinished
    case Some(a) =>
      println("Recieved strange req: $a")
      ???

  }

  var childrenPending = 0

  def buildChild(e: Int): ActorRef = context.actorOf(BinaryTreeNode.props(e, initiallyRemoved = false))

  // optional
  /**
   * `expected` is the set of ActorRefs whose replies we are waiting for,
   * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
   */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(id) =>
      println(s"[copyying mode] OperationFinished($id): $insertConfirmed <- $sender")
      if (sender == self) {
        println(s"Confirming current node($self) copy insert confirmation!")
        context become copying(expected, true)
      } else {
        if (insertConfirmed && expected.isEmpty) {
          println("copying(): confirmed = true, expected = (), NOTIFY PARENT -> CopyFinished")
          context.parent ! CopyFinished
          println("copying(): OperationFinished(): Restoring context to NORMAL")
          context become normal
          //       context.stop(self)
        }

        if (!expected.isEmpty) {
          if (expected.contains(sender)) {
            println(s"One child reported as finished, will continue")
            context become (copying(expected - sender, false))
            self ! OperationFinished(id)
          } else {
            println(s"!!!!!OperationFinished($id) while copying: expected does NOT contain $sender: $expected")
            context become copying(expected, true)
          }
        }
      }
  }

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
