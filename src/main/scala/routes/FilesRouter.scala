package routes

import java.io.{File, FileInputStream}

import actors.UniqueFileNameGenerateActor
import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.{ByteString, Timeout}
import commons.HashOperations
import org.omg.CosNaming.NamingContextPackage.NotFound
import spray.json.{JsNumber, JsObject, JsString}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * routes
 *
 * @author colin
 * @version 1.0, 2020/11/10
 * @since 0.4.1
 */
class FilesRouter(fileNameGenerateActor: ActorRef[UniqueFileNameGenerateActor.Command])
                 (implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with SprayJsonSupport{
  implicit val timeout: Timeout = 3.seconds

  val config = this.system.settings.config
  val fileSaveBaseDir = config.getString("app.file-base-dir")

  private def uploadFile = (post & path("files")) {
    this.log.info("upload request received---")
    fileUpload("file") {
      case (metadata, byteSource) =>
        this.log.info("start uploading file {} ...", metadata.getFileName)
        val fileUploadResultF: Future[_] = fileNameGenerateActor.ask(UniqueFileNameGenerateActor.GetFileName)
          .flatMap { fileName =>
            val fileExtension = metadata.getFileName.split("\\.").last
            val file = new File(s"${fileSaveBaseDir}/${fileName}.${fileExtension}")
            file.getParentFile.mkdirs()
            val fileSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(file.toPath())
            byteSource.runWith(fileSink)
              .map { _ =>
                val hash = HashOperations.computeHashFromStream(new FileInputStream(file), HashOperations.Sha256)
                file.renameTo(new File(s"${fileSaveBaseDir}/${hash}.${fileExtension}"))
              }
          }
        onComplete(fileUploadResultF) {
          case Success(_) =>
            this.log.info(s"upload file successfully, fileName:${metadata.getFileName}")
            complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
          case Failure(e) =>
            this.log.warn("file upload fail, fileName: {}, msg: {}, stack: {}", metadata.getFileName, e.getMessage, e.fillInStackTrace())
            complete(HttpResponse(status = InternalServerError, entity = JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)).toString()))
        }
    }
  }

  private def downloadFile = (get & path( "files" / ) & parameter("filePath")) { filePath =>
    this.log.info("start downloading file..., filePath: {}", filePath)
    val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] = S3.download(this.awsS3Config.bucketName, filePath)
    onComplete(s3File.runWith(Sink.head)) {
      case Success(sourceAndMetadataOpt) =>
        sourceAndMetadataOpt match {
          case None =>
            this.log.warn("resource not found on aws s3, filePath: {}", filePath)
            complete(HttpResponse(NotFound))
          case Some((source, metadata)) =>
            log.info("file metadata, filePath: {}, length: {}, last modified: {}", filePath, metadata.getContentLength, metadata.getLastModified)
            complete(HttpEntity(ContentTypes.NoContentType, source))
        }
      case Failure(e) =>
        log.warn("future fail, msg: {}, stack: {}", e.getMessage(), e.fillInStackTrace())
        complete(HttpResponse(InternalServerError))
    }
  }

//  private def deleteFile = (delete & path("oss" / "files") & parameter("filePath")) { filePath =>
//    this.log.info("start deleting file..., filePath: {}", filePath)
//    val deleteF = S3.deleteObject(this.awsS3Config.bucketName, filePath).run()
//    onComplete(deleteF) {
//      case Success(_) =>
//        this.log.info("file deleted success, filePath: {}", filePath)
//        complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
//      case Failure(e) =>
//        this.log.warn("file delete fail, filePath: {}, msg: {}, stack: {}", filePath, e.getMessage(), e.fillInStackTrace())
//        complete(HttpResponse(InternalServerError))
//    }
//  }

//  private def getFileMetadata = (get & path("oss" / "files" / "metadata") & parameter("filePath")) { filePath =>
//    this.log.info("start checking file existence..., filePath: {}", filePath)
//    val objMetaDataSource: Source[Option[ObjectMetadata], NotUsed] = S3.getObjectMetadata(this.awsS3Config.bucketName, filePath)
//    onComplete(objMetaDataSource.runWith(Sink.head)) {
//      case Success(dataOpt) =>
//        dataOpt match {
//          case None =>
//            this.log.info("file not exist, filePath: {}", filePath)
//            complete(HttpResponse(NotFound))
//          case Some(metadata) =>
//            this.log.info("file exist, contentType: {}, contentLength: {}, lastModified: {},  filePath: {}", metadata.contentType, metadata.contentLength, metadata.lastModified, filePath)
//            complete(JsObject("code" -> JsNumber(0),
//              "msg" -> JsString("file exist"),
//              "data" -> JsObject(
//                "contentType" -> JsString(metadata.contentType.getOrElse("")),
//                "contentLength" -> JsNumber(metadata.contentLength),
//                "lastModified" -> JsString(metadata.lastModified.toIsoDateTimeString())
//              )))
//        }
//      case Failure(e) =>
//        this.log.warn("check file exist failed, msg: {}, stack: {}", e.getMessage(), e.fillInStackTrace())
//        complete(HttpResponse(InternalServerError))
//    }
//  }

  val routes: Route = concat(
    uploadFile,
//    downloadFile,
//    deleteFile,
//    getFileMetadata
  )
}
