package suggestions

import language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import rx.lang.scala._
import org.scalatest._
import gui._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class WikipediaApiTest extends FunSuite {

  object mockApi extends WikipediaApi {
    def wikipediaSuggestion(term: String) = Future {
      if (term.head.isLetter) {
        for (suffix <- List(" (Computer Scientist)", " (Footballer)")) yield term + suffix
      } else {
        List(term)
      }
    }
    def wikipediaPage(term: String) = Future {
      "Title: " + term
    }
  }

  import mockApi._

  test("WikipediaApi should make the stream valid using sanitized") {
    val notvalid = Observable.just("erik", "erik meijer", "martin")
    val valid = notvalid.sanitized

    var count = 0
    var completed = false

    val sub = valid.subscribe(
      term => {
        assert(term.forall(_ != ' '))
        count += 1
      },
      t => assert(false, s"stream error $t"),
      () => completed = true)
    assert(completed && count == 3, "completed: " + completed + ", event count: " + count)
  }
  
  def m(x: Try[Int], num: Int) = {
      x match {
        case Success(num) => 
        case _ => assert(false, s"values is not equal to List(Success($num))")
      }
    }
  
  
  test("WikipediaApi should correctly use concatRecovered") {

    val requests = Observable.just(1, 2, 3, 4, 5)

    val remoteComputation = (num: Int) => if (num != 4) Observable.just(num) else Observable.error(new Exception)
    val responses = requests concatRecovered remoteComputation
    
    val rl = responses.toBlocking.toList
    val (aa :: bb :: cc :: dd :: ee :: Nil) = rl
    m(aa, 1)
    m(bb, 2)
    m(cc, 3)
    
    m(ee, 5)
    dd match {
      case Failure(ff) => assert(ff.isInstanceOf[Exception])
      case _ => fail("should not be this")
    }
    
  }

  test("WikipediaApi recovered") {
    val c = (num: Int) => if (num != 4) Observable.just(num) else Observable.error(new RuntimeException)
    val requests = Observable.just(1, 2, 3, 4).map { x => c(x) }.flatten
    var oo: Observer[Int] = null


    val responses = requests.recovered
    println("before block")
    val rr = responses.toBlocking.toList
    println("rrr " + rr)
    val (aa :: bb :: cc :: dd :: Nil) = rr
    dd match { 
      case Failure(fl) => assert (fl.isInstanceOf[RuntimeException])
      case _ => assert(false, "Failed, success instead of success")
    }
    
    m(aa, 1)
    m(bb, 2)
    m(cc, 4)
 
  }

  test("WikipediaApi timeout should return the first value, and complete without errors") {
    val requests = Observable.just(10, 20, 30, 40, 50).zip(Observable.interval(700 millis))

    val responses = requests.timedOut(1L)

    var sum: Integer = 0
    responses.subscribe {
      x => sum = sum + x._1
    }

    Thread.sleep(2000)
    assert(sum == 10)
  }
}
