package routes

import java.io.{File, FileInputStream}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.typed.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Sink, Source, StreamConverters}
import akka.util.{ByteString, Timeout}
import commons.HashOperations
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
class FilesRouter()(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with SprayJsonSupport {
  implicit val timeout: Timeout = 3.seconds

  val config = this.system.settings.config

  val fileSaveBaseDir = config.getString("app.file-base-dir")
  val fileBaseUrl = config.getString("app.file-base-url")
  val serverId = system.settings.config.getInt("app.server-id")
  val serverStartTimeInMillis = System.currentTimeMillis()
  val fileSequenceNrGenerator = new AtomicLong()

  val DIR_NAME_LENGTH = 4
  val HASH_STR_LENGTH = 64

  private def hashToAbsolutePath(hash: String, fileExtension: String): String = {
    val fileHashPart = (0 until hash.size)
      .map {
        case i if i % DIR_NAME_LENGTH != 3 || i == HASH_STR_LENGTH - 1 =>
          hash.charAt(i).toString()
        case i =>
          s"${hash.charAt(i)}/"
      }
      .mkString("")
    val currentDirPath = new File("").getAbsolutePath
    s"${currentDirPath}/${fileSaveBaseDir}/${fileHashPart}.${fileExtension}"
  }

  private def uploadFile = (post & path("files")) {
    this.log.info("upload request received---")
    fileUpload("file") {
      case (metadata, byteSource) =>
        this.log.info("start uploading file {} ...", metadata.getFileName)
        val fileExtension = metadata.getFileName.split("\\.").last
        val file = new File(s"${fileSaveBaseDir}/${this.serverStartTimeInMillis}-${fileSequenceNrGenerator}.${fileExtension}")
        file.getParentFile.mkdirs()
        val fileSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(file.toPath())
        val fileUrlFuture: Future[String] = byteSource.runWith(fileSink)
          .map { _ =>
            val hash = HashOperations.computeHashFromStream(new FileInputStream(file), HashOperations.Sha256)
            val fileDistPath = hashToAbsolutePath(hash, fileExtension)
            val distFile = new File(fileDistPath)
            distFile.getParentFile.mkdirs()
            println(s"file dist path: ${fileDistPath}")
            file.renameTo(new File(fileDistPath))
            s"${this.fileBaseUrl}/${hash}.${fileExtension}"
          }
        onComplete(fileUrlFuture) {
          case Success(url) =>
            this.log.info(s"upload file successfully, fileName:${metadata.getFileName}")
            complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success"), "data" -> JsString(url)))
          case Failure(e) =>
            this.log.warn("file upload fail, fileName: {}, msg: {}, stack: {}", metadata.getFileName, e.getMessage, e.fillInStackTrace())
            complete(HttpResponse(status = InternalServerError, entity = JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)).toString()))
        }
    }
  }

  val hashAndExtensionRegex = "([0-9 | a-f | A-F]{64})\\.([\\w]+)".r

  private def getFile = (get & path("files" / "public"/ Remaining)) { fileHashAndExtension =>
    (fileHashAndExtension match {
      case hashAndExtensionRegex(fileHash, fileExtension) =>
        this.log.info("start getting file..., fileHash: {}, fileExtension: {}", fileHash, fileExtension)
        Some((fileHash, fileExtension))
      case _ =>
        this.log.info("illegal fileName: {}", fileHashAndExtension)
        None
    })
      .flatMap { case (fileHash, fileExtension) =>
        val targetFilePath: String = hashToAbsolutePath(fileHash, fileExtension)
        val targetFile: File = new File(targetFilePath)
        if (!targetFile.exists()) {
          this.log.info("file not exist, targetFilePath: {}", targetFilePath)
          None
        } else {
          val source: Source[ByteString, Future[IOResult]] = StreamConverters.fromInputStream(() => new FileInputStream(targetFile))
          Some(complete(HttpEntity(ContentTypes.NoContentType, source)))
        }
      }
      .getOrElse {
        complete(HttpResponse(StatusCodes.NotFound))
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
    getFile
    //    deleteFile,
    //    getFileMetadata
  )
}
