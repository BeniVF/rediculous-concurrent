package io.chrisdavenport.rediculous.concurrent

import cats._
import cats.syntax.all._
import cats.data.NonEmptyList
import io.chrisdavenport.rediculous.RedisConnection
import cats.effect._
import cats.effect.syntax._
import scala.concurrent.duration.FiniteDuration
import io.chrisdavenport.rediculous._
import io.chrisdavenport.rediculous.RedisProtocol._
import io.chrisdavenport.rediculous.RedisCommands._
import io.chrisdavenport.rediculous.RedisTransaction.TxResult.{Success, Aborted, Error}
import java.util.UUID


object RedisLock {

  def tryAcquireLock[F[_]: Concurrent: Timer](
    connection: RedisConnection[F],
    lockname: String,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
  ): F[Option[UUID]] = {
    val lockName = "lock:" ++ lockname 
    def create: F[Option[UUID]] = for {
      identifier <- Sync[F].delay(java.util.UUID.randomUUID())
      status <- RedisCommands.set(lockName, identifier.toString(), 
        RedisCommands.SetOpts(None, Some(lockTimeout.toMillis), Some(Condition.Nx), false)
      ).run(connection)
      out = status match {
        case Status.Ok => Some(identifier)
        case Status.Pong => None
        case Status.Status(getStatus) => None
      }
    } yield out
    Concurrent.timeout(create, acquireTimeout)
  }

  def shutdownLock[F[_]: Concurrent](
    connection: RedisConnection[F],
    lockname: String,
    identifier: UUID
  ): F[Unit] = {
    val lockName = "lock:" ++ lockname 
    (RedisCtx[RedisPipeline].keyed[Status](lockName, NonEmptyList.of("WATCH", lockName)) *>
          RedisCommands.get[RedisPipeline](lockName)
        ).pipeline.run(connection).flatMap{
          case Some(value) if value === identifier.toString => 
            RedisCommands.del[RedisTransaction](lockName)
              .transact
              .run(connection)
              .flatMap{
                case Success(_) => Applicative[F].unit
                case Aborted => shutdownLock(connection, lockName, identifier)
                case Error(value) => new Throwable(s"lock shutdown for $lockName encountered error $value").raiseError[F, Unit]
              }
          case _ => Applicative[F].unit
        }
  }

  def tryAcquireLockWithTimeout[F[_]: Concurrent: Timer](
    connection: RedisConnection[F],
    lockname: String,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
  ): Resource[F, Boolean] = {
    val lockName = "lock:" ++ lockname 
    def create: F[Option[String]] = for {
      identifier <- Sync[F].delay(java.util.UUID.randomUUID())
      status <- RedisCommands.set(lockName, identifier.toString(), 
        RedisCommands.SetOpts(None, Some(lockTimeout.toMillis), Some(Condition.Nx), false)
      ).run(connection)
      out = status match {
        case Status.Ok => Some(identifier.toString())
        case Status.Pong => None
        case Status.Status(getStatus) => None
      }
    } yield out

    def shutdown(opt: Option[String]): F[Unit] = opt match {
      case Some(identifier) => 
        (RedisCtx[RedisPipeline].keyed[Status](lockName, NonEmptyList.of("WATCH", lockName)) *>
          RedisCommands.get[RedisPipeline](lockName)
        ).pipeline.run(connection).flatMap{
          case Some(value) if value === identifier => 
            RedisCommands.del[RedisTransaction](lockName)
              .transact
              .run(connection)
              .flatMap{
                case Success(_) => Applicative[F].unit
                case Aborted => shutdown(Some(identifier))
                case Error(value) => new Throwable(s"lock shutdown for $lockName encountered error $value").raiseError[F, Unit]
              }
          case _ => Applicative[F].unit
        }
      case None => Applicative[F].unit
    }

    val createWithTimeout = Concurrent.timeout(create, acquireTimeout)
    Resource.make(createWithTimeout)(shutdown).map(_.isDefined)
  }

  def acquireLockWithTimeout[F[_]: Concurrent: Timer](
    connection: RedisConnection[F],
    lockname: String,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
  ): Resource[F, Unit] = {
    def go: Resource[F, Unit] = tryAcquireLockWithTimeout(connection, lockname, acquireTimeout, lockTimeout).flatMap{
      case true => Resource.pure[F, Unit](())
      case false => go
    }
    go 
  }

}