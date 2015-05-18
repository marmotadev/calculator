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
  test("WikipediaApi should correctly use concatRecovered") {
    val requests = Observable.just(1, 2, 3)
    val remoteComputation = (n: Int) => Observable.just(0 to n: _*)
    val responses = requests concatRecovered remoteComputation
    val sum = responses.foldLeft(0) { (acc, tn) =>
      tn match {
        case Success(n) => acc + n
        case Failure(t) => throw t
      }
    }
    var total = -1
    val sub = sum.subscribe {
      s => total = s
    }
    assert(total == (1 + 1 + 2 + 1 + 2 + 3), s"Sum: $total")
  }

  test("WikipediaApi recovered") {
    val requests = Observable.just(1, 2, 3, new RuntimeException())
    //    val remoteComputation = (n: Int) => Observable.just(0 to n : _*)
    val responses = requests.recovered
    var cnt: Integer = 0
    responses.doOnEach { x =>
      val b = x.get
      b match {
        case Success(1) => cnt += 1
        case Success(2) => cnt += 1
        case Success(3) => cnt += 1
        case Failure(e) => assert(e.isInstanceOf[RuntimeException])
        case _          => assert(false, "blogai")
      }
      assert(cnt == 3)
    }
  }

   test("WikipediaApi timeout should return the first value, and complete without errors") {
    val requests = Observable.just(1, 2, 3).zip(Observable.interval(700 millis)).timedOut(1L)

    val responses = requests.timeout(Duration(500, TimeUnit.MILLISECONDS))
    
    var cnt = responses.foldLeft(0) { (num, bum) => num + bum._1
      
    }
    cnt.subscribe { x => assert (x == 1)}
    
  }
}
