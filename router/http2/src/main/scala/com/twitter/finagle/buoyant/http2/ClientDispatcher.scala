package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.Service
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.io.Reader
import com.twitter.util.{Future, Promise, Return, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ClientDispatcher(
  transport: Transport[Http2StreamFrame, Http2StreamFrame]
) extends Service[Request, Response] {

  private[this] val log = Logger.get(getClass.getName)

  // TODO handle overflow
  private[this] val _id = new AtomicInteger(1)
  private[this] def nextId() = _id.getAndAdd(2)

  private[this] case class Stream(
    id: Int,
    manager: StreamState.Manager,
    response: Promise[Response]
  )
  private[this] val streams = new ConcurrentHashMap[Int, Stream]

  def apply(req: Request): Future[Response] = {
    val streamId = nextId()
    val manager = new StreamState.Manager
    val rspp = Promise[Response]
    streams.putIfAbsent(streamId, Stream(streamId, manager, rspp)) match {
      case null => // expected
      case state =>
        return Future.exception(new IllegalStateException(s"stream $streamId already exists as $state"))
    }

    val frame = headers(req.headers, req.data.isEmpty).setStreamId(streamId)
    manager.send(req) match {
      case (StreamState.Idle, StreamState.Open(_, Local(reader, trailers))) =>
        // New request, with data to be sent...

        transport.write(frame).before {
          // TODO interrupts
          val writing = writeLoop(streamId, reader, trailers)
          val _ = writing.onFailure { e =>
            log.error(e, s"client.dispatch: $streamId writing error")
          }

          rspp
        }

      case (StreamState.Idle, StreamState.RemoteOpen(_)) =>
        // log.info(s"client.dispatch: $streamId writing $frame")
        // New request, without data to send
        transport.write(frame).before(rspp)

      case (s0, s1) => Future.exception(new IllegalStateException(s"stream $streamId $s0 -> $s1"))
    }
  }

  private[this] def headers(orig: Headers, eos: Boolean): Http2HeadersFrame = {
    val headers = orig match {
      case h: Netty4Headers => h.underlying
      case hs =>
        val headers = new DefaultHttp2Headers
        for ((k, v) <- hs.toSeq) headers.add(k, v)
        headers
    }
    new DefaultHttp2HeadersFrame(headers, eos)
  }

  private[this] val reading = {
    def loop(): Future[Unit] = {
      // log.info(s"client.dispatch: readLoop reading")
      transport.read().flatMap { frame =>
        // log.info(s"client.dispatch: readLoop read $frame")

        streams.get(frame.streamId) match {
          case null =>
            log.error(s"no stream id on $frame")
            loop()

          case stream@Stream(_, manager, response) =>
            val (s0, s1) = manager.recv(frame)
            // log.info(s"client.dispatch: readLoop state0 $s0")
            // log.info(s"client.dispatch: readLoop state1 $s1")

            (s0, s1, frame) match {

              /*
               * Response headers
               */

              case (StreamState.RemoteIdle(), StreamState.RemoteActive(reader, trailers), f: Http2HeadersFrame) =>
                val rsp = Response(ResponseHeaders(f.headers), Some(DataStream(reader, trailers)))
                // log.info(s"client.dispatch: read streaming response")
                response.setValue(rsp)
                loop()

              case (StreamState.RemoteIdle(), StreamState.RemoteClosed(), f: Http2HeadersFrame) =>
                val rsp = Response(ResponseHeaders(f.headers), None)
                response.setValue(rsp)
                loop()

              /*
               * Data
               */

              case (StreamState.RemoteActive(rw, _), StreamState.RemoteActive(_, _), f: Http2DataFrame) =>
                val sz = f.content.readableBytes + f.padding
                rw.write(ByteBufAsBuf.Owned(f.content)).before {
                  // TODO f.content.release()
                  updateCapacity(stream, sz).before {
                    loop()
                  }
                }

              case (StreamState.RemoteActive(rw, trailers), StreamState.RemoteClosed(), f: Http2DataFrame) =>
                rw.write(ByteBufAsBuf.Owned(f.content)).before(rw.close()).transform {
                  case t@Throw(e) =>
                    trailers.updateIfEmpty(Throw(e))
                    Future.const(t)

                  case Return(_) =>
                    trailers.updateIfEmpty(Return(None))
                    loop()
                }

              /*
               * Trailers
               */

              case (StreamState.RemoteActive(rw, trailers), StreamState.RemoteClosed(), f: Http2HeadersFrame) =>
                log.info(s"client.dispatch [${f.name} ${f.streamId}] closing")
                f.headers.size
                rw.close().before {
                  log.info(s"client.dispatch [${f.name} ${f.streamId}] closed")
                  trailers.setValue(Some(Headers(f.headers)))
                  loop()
                }

              case (s0, s1, f) =>
                Future.exception(new IllegalStateException(s"[${f.name} ${f.streamId}] $s0 -> $s1"))
            }
        }
      }
    }

    loop().onFailure { e =>
      log.error(e, "client.dispatch: readLoop")
    }
  }

  private[this] def updateCapacity(stream: Stream, bytes: Int): Future[Unit] = {
    val frame = new DefaultHttp2WindowUpdateFrame(bytes).setStreamId(stream.id)
    transport.write(frame)
  }

  private[this] def writeLoop(streamId: Int, reader: Reader, trailers: Future[Option[Headers]]): Future[Unit] = {
    def loop(): Future[Unit] = {
      reader.read(Int.MaxValue).flatMap {
        case None =>
          val eos = true
          trailers.flatMap {
            case None =>
              val frame = new DefaultHttp2DataFrame(eos).setStreamId(streamId)
              transport.write(frame)

            case Some(trailers) =>
              val frame = headers(trailers, eos).setStreamId(streamId)
              transport.write(frame)
          }

        case Some(buf) =>
          val bb = try BufAsByteBuf.Owned(buf).retain() catch {
            case e: Throwable =>
              log.error(e, "client.dispatch: retain")
              throw e
          }
          val frame = new DefaultHttp2DataFrame(bb, false /*eos*/ ).setStreamId(streamId)
          transport.write(frame).before(loop())
      }
    }

    loop()
  }

}