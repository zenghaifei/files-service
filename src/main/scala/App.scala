import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import com.github.swagger.akka.SwaggerSite
import routes.FilesRouter

object App extends SwaggerSite {

  def main(args: Array[String]): Unit = {
    ActorSystem(Behaviors.setup[String] { context =>
      implicit val system = context.system
      import context.executionContext
      val config = context.system.settings.config
      val filesRoute = new FilesRouter().routes
      val host = "0.0.0.0"
      val port = config.getInt("server.port")
      Http().newServerAt(host, port).bind(filesRoute)
      context.log.info(s"server started at ${host}:${port}")
      Behaviors.same
    }, "files-service")
  }
}

