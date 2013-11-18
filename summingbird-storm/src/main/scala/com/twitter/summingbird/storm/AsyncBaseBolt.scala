/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.summingbird.storm

import backtype.storm.tuple.Tuple
import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.online.Queue
import com.twitter.summingbird.storm.option.{AnchorTuples, MaxWaitingFutures}
import com.twitter.util.{Await, Future, Return, Throw, Try}
import java.util.{ Arrays => JArrays, List => JList }

abstract class AsyncBaseBolt[I, O](metrics: () => TraversableOnce[StormMetric[_]],
  anchorTuples: AnchorTuples,
  maxWaitingFutures: MaxWaitingFutures,
  hasDependants: Boolean) extends BaseBolt[I, O](metrics, anchorTuples, hasDependants) {

  /** If you can use Future.value below, do so. The double Future is here to deal with
   * cases that need to complete operations after or before doing a FlatMapOperation or
   * doing a store merge
   */
  def apply(tup: Tuple, in: (Timestamp, I)): Future[Iterable[(JList[Tuple], Future[TraversableOnce[(Timestamp, O)]])]]

  private lazy val futureQueue = Queue[Future[Unit]]()
  private lazy val channel = Queue[(JList[Tuple], Try[TraversableOnce[(Timestamp, O)]])]()

  override def execute(tuple: Tuple) {
    /**
     * System ticks come with a fixed stream id
     */
    if(!tuple.getSourceStreamId.equals("__tick")) {
      // This not a tick tuple so we need to start an async operation
      val tsIn = decoder.invert(tuple.getValues).get // Failing to decode here is an ERROR

      val fut = apply(tuple, tsIn)
        .onSuccess { iter =>
          // Collect the result onto our channel
          val (putCount, maxSize) = iter.foldLeft((0, 0)) { case ((p, ms), (tups, res)) =>
            res.respond { t => channel.put((tups, t)) }
            // Make sure there are not too many outstanding:
            val count = futureQueue.put(res.unit)
            (p + 1, ms max count)
          }

          if(maxSize > maxWaitingFutures.get) {
            /*
             * This can happen on large key expansion.
             * May indicate maxWaitingFutures is too low.
             */
            logger.debug(
              "Exceeded maxWaitingFutures(%d): waiting = %d, put = %d"
                .format(maxWaitingFutures.get, maxSize, putCount)
              )
          }
        }
        .onFailure { thr => fail(JArrays.asList(tuple), thr) }

      futureQueue.put(fut.unit)
    }
    // always empty the channel, even on tick
    emptyQueue
  }

  protected def forceExtraFutures {
    val toForce = futureQueue.trimTo(maxWaitingFutures.get)
    if(!toForce.isEmpty) Await.result(Future.collect(toForce))
  }

  protected def emptyQueue = {
    // don't let too many futures build up
    forceExtraFutures
    // Handle all ready results now:
    channel.foreach { case (tups, res) =>
      res match {
        case Return(outs) => finish(tups, outs)
        case Throw(t) => fail(tups, t)
      }
    }
  }
}
