package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

/**
 * net.juzix
 *
 * @author colin
 * @version 1.0, 2020/11/28
 * @since 0.4.1
 */
object UniqueFileNameGenerateActor {

  sealed trait Command

  case class GetFileName(from: ActorRef[String]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    val serverId = context.system.settings.config.getInt("app.server-id")
    val serverStartTimeInMillis = System.currentTimeMillis()
    context.log.info(s"actor starting..., serverId: ${serverId}")

    def waiting(sequenceNr: Long): Behavior[Command] = {
      Behaviors.receive { (ctx, message) =>
        message match {
          case GetFileName(from) =>
            ctx.log.debug("received GetFileName message")
            val fileName = s"${serverStartTimeInMillis}-${sequenceNr + 1}"
            from ! fileName
            waiting(sequenceNr + 1)
          case message =>
            ctx.log.warn(s"received unknown message: ${message}")
            Behaviors.same
        }
      }
    }

    waiting(0)
  }

}
