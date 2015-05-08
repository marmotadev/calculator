package nodescala

import scala.language.postfixOps
import scala.util.{ Try, Success, Failure }
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{ async, await }
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite with Matchers {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }
  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("All should return all") {
    val always = Future.all(List(
      Future.always(6),
      Future {
        Thread.sleep(100);
        5
      }))

    val one :: two :: Nil = Await.result(always, 1 second)
    one should equal(6)
    two should equal(5)
  }

  test("First future returned first") {
    val r = Future.any(List(
      Future {
        Thread.sleep(500)
        "Second"
      },
      Future {
        Thread.sleep(100);
        "First"
      }))

    val one = Await.result(r, 1 second)
    one should equal("First")

  }

  test("Delay returns value after delay") {
    var f = Future.delay(10 millisecond)

    intercept[TimeoutException] {
      val one = Await.result(f, 1 millisecond)
    }

    f = Future.delay(10 millisecond)

    Await.result(f, 1000 millisecond)

  }

  def cancelableFunction(f: Future[Unit]) = (ct: CancellationToken) => {

  }
  test("After canncellable is canceled, it is not completed") {

    var cf: CancellationToken => Future[Unit] = ct => Future {
      blocking {
        Thread.sleep(10000)
      }
    }

    var s: Subscription = Future.run()(cf)
    var res = s.unsubscribe()

  }

  test("continueWith") {
    val first: Future[Int] = Future[Int] {
      Thread.sleep(100)
      5
    }
    val second: (Future[Int]) => String = (f1: Future[Int]) => {
      def numtos(num: Int) = {
        num match {
          case 1 => "First"
          case 5 => "Five"
          case 0 => "Zero"
        }
      }
      val p = Promise[String]()

      f1 onComplete {
        case Success(num) =>
          p complete {
            Try {
              numtos(num)
            }
          }
      }
      Await.result(p.future, Duration.Inf)
    }
    val resultFuture: Future[String] = first.continueWith(second)
    val r = Await.result(resultFuture, Duration.Inf)

    r should be("Five")
  }

  test("Future.continueWith should handle exceptions thrown by the user specified continuation function") {
    val first: Future[Int] = Future[Int] {
        Thread.sleep(100)
        5
    }
    val second: (Future[Int]) => String = (f1: Future[Int]) => {
        val p = Promise[String]()

        f1 onComplete {
          throw new RuntimeException("Test errors")
        }
        Await.result(p.future, Duration.Inf)
    }
    val resultFuture: Future[String] = first.continueWith(second)

    Await.ready(resultFuture, Duration.Inf)

    val r = Await.result(resultFuture.failed, Duration.Inf)

    assert(r.isInstanceOf[RuntimeException])
    r.getMessage should be("Test errors")
  }
  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))
    test(immutable.Map())
    dummySubscription.unsubscribe()
  }

  test("Server should cancel a long-running or infinite response") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request =>
        {
          blocking {
            println("Simulating long response")
            Thread.sleep(2000)
            try for (kv <- request.iterator) yield (kv + "\n").toString
            finally println("Long resp finished")
          }
        }
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request, ctx: String = "/testDir") {
      val webpage = dummy.emit(ctx, req)
//      val temper = Duration.Inf // 1 second
      val temper = 1 second
      val content = Await.result(webpage.loaded.future, temper)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == "", s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))
    test(immutable.Map())
    println("Unsubscribe!")
    dummySubscription.unsubscribe()
//    test(immutable.Map("ReqiestAferSubscription" -> List("Should not be handled.")))
  }

}




