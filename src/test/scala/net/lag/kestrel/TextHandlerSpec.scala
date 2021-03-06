/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.net.InetSocketAddress
import com.twitter.conversions.time._
import com.twitter.naggati.test.TestCodec
import com.twitter.util.Time
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.ChannelGroup
import org.specs.Specification
import org.specs.mock.{JMocker, ClassMocker}

class TextHandlerSpec extends Specification with JMocker with ClassMocker {
  def wrap(s: String) = ChannelBuffers.wrappedBuffer(s.getBytes)

  "TextCodec" should {
    "get request" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)

      codec(wrap("get foo\r\n")) mustEqual List(TextRequest("get", List("foo"), Nil))
      codec(wrap("get f")) mustEqual Nil
      codec(wrap("oo\r\n")) mustEqual List(TextRequest("get", List("foo"), Nil))

      codec(wrap("get foo 100")) mustEqual Nil
      codec(wrap("\n")) mustEqual List(TextRequest("get", List("foo", "100"), Nil))
    }

    "put request" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)

      codec(wrap("put foo:\n")) mustEqual Nil
      codec(wrap("hello\n")) mustEqual Nil
      val stream = codec(wrap("\n"))
      stream.size mustEqual 1
      val r = stream(0).asInstanceOf[TextRequest]
      r.command mustEqual "put"
      r.args mustEqual List("foo")
      r.items.map { new String(_) } mustEqual List("hello")
    }

    "quit request" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)
      codec(wrap("QUIT\r\n")) mustEqual List(TextRequest("quit", Nil, Nil))
    }

    "success response" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)
      codec.send(CountResponse(23)) mustEqual List("+23\n")
    }

    "error response" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)
      codec.send(ErrorResponse("Bad karma")) mustEqual List("-Bad karma\n")
    }

    "empty response" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)
      codec.send(ItemResponse(None)) mustEqual List("*\n")
    }

    "item response" in {
      val codec = new TestCodec(TextCodec.read, TextCodec.write)
      codec.send(ItemResponse(Some("hello".getBytes))) mustEqual List(":hello\n")
    }
  }

  "TextHandler" should {
    val channel = mock[Channel]
    val channelGroup = mock[ChannelGroup]
    val queueCollection = mock[QueueCollection]

    def equal[A](a: A) = will(beEqual(a))

    "get request" in {
      val function = capturingParam[Function[Option[QItem], Unit]]

      expect {
        one(channel).getRemoteAddress() willReturn new InetSocketAddress(0)
        one(channelGroup).add(channel)
      }

      val textHandler = new TextHandler(channelGroup, queueCollection, 10, None)
      textHandler.handleUpstream(null, new UpstreamChannelStateEvent(channel, ChannelState.OPEN, true))

      "closes transactions" in {
        expect {
          one(queueCollection).confirmRemove("test", 100)
          one(queueCollection).remove(equal("test"), equal(None), equal(true), equal(false))(function.capture)
        }

        textHandler.pendingTransactions.add("test", 100)
        textHandler.pendingTransactions.size("test") mustEqual 1
        textHandler.handle(TextRequest("get", List("test"), Nil))
        textHandler.pendingTransactions.size("test") mustEqual 0
      }

      "with timeout" in {
        Time.withCurrentTimeFrozen { time =>
          expect {
            one(queueCollection).remove(equal("test"), equal(Some(500.milliseconds.fromNow)), equal(true), equal(false))(function.capture)
          }

          textHandler.handle(TextRequest("get", List("test", "500"), Nil))
        }
      }

      "empty queue" in {
        expect {
          one(queueCollection).remove(equal("test"), equal(None), equal(true), equal(false))(function.capture)
          one(channel).write(ItemResponse(None))
        }

        textHandler.handle(TextRequest("get", List("test"), Nil))
        function.captured(None)
      }

      "item ready" in {
        val response = capturingParam[ItemResponse]

        expect {
          one(queueCollection).remove(equal("test"), equal(None), equal(true), equal(false))(function.capture)
          one(channel).write(response.capture)
        }
        textHandler.handle(TextRequest("get", List("test"), Nil))
        function.captured(Some(QItem(Time.epoch, None, "hello".getBytes, 0)))
        new String(response.captured.data.get) mustEqual "hello"
      }
    }

    "put request" in {
      val bytes = capturingParam[Array[Byte]]

      expect {
        one(channel).getRemoteAddress() willReturn new InetSocketAddress(0)
        one(channelGroup).add(channel)
        one(queueCollection).add(equal("test"), bytes.capture, equal(None)) willReturn true
        one(channel).write(CountResponse(1))
      }

      val textHandler = new TextHandler(channelGroup, queueCollection, 10, None)
      textHandler.handleUpstream(null, new UpstreamChannelStateEvent(channel, ChannelState.OPEN, true))
      textHandler.handle(TextRequest("put", List("test"), List("hello".getBytes)))
      new String(bytes.captured) mustEqual "hello"
    }
  }
}
