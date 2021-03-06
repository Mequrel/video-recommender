package ztis.user_video_service

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, Props}
import akka.event.LoggingReceive
import org.neo4j.graphdb.GraphDatabaseService
import ztis.user_video_service.ServiceActorMessages.{NextInternalIDResponse, NextInternalIDRequest}
import ztis.user_video_service.UserServiceActor._
import ztis.user_video_service.persistence.{Metadata, MetadataRepository, UnitOfWork, UserRepository}

object UserServiceActor {

  case class RegisterTwitterUser(externalUserName: String, externalUserID: Long)

  case class RegisterWykopUser(externalUserName: String)

  case class TwitterUserRegistered(internalUserID: Int, request: RegisterTwitterUser)

  case class WykopUserRegistered(internalUserID: Int, request: RegisterWykopUser)

  def props(graphDatabaseService: GraphDatabaseService,
            userRepository: UserRepository,
            metadataRepository: MetadataRepository): Props = {
    Props(classOf[UserServiceActor], graphDatabaseService, userRepository, metadataRepository)
  }
}

class UserServiceActor(graphDatabaseService: GraphDatabaseService,
                       userRepository: UserRepository,
                       metadataRepository: MetadataRepository) extends Actor with ActorLogging with UnitOfWork {

  private implicit val _service = graphDatabaseService

  private var nextInternalID: Int = fetchMetadata.nextUserInternalID

  private var tempNextInternalID: Int = nextInternalID

  private def fetchMetadata: Metadata = {
    unitOfWork { () =>
      metadataRepository.metadata
    }
  }

  override def receive: Receive = LoggingReceive {
    case NextInternalIDRequest => {
      sender() ! NextInternalIDResponse(nextInternalID)
    }
    case request: RegisterTwitterUser => {
      handleInTryCatch(request, handleTwitterRequest)
    }
    case request: RegisterWykopUser => {
      handleInTryCatch(request, handleWykopRequest)
    }
  }

  private def handleInTryCatch[IN, OUT](request: IN, processFunction: IN => OUT): Unit = {
    try {
      val result: OUT = unitOfWork(() => processFunction(request))
      sender() ! result
    } catch {
      case e: Exception => {
        log.error(e, s"Something went wrong during processing of $request")
        /*
        We are rolling back tempNextInternalID to the one that was present before we started to process given request
         */
        tempNextInternalID = nextInternalID
        sender() ! Failure(e)
      }
    }
    nextInternalID = tempNextInternalID
  }

  private def handleTwitterRequest(request: RegisterTwitterUser): TwitterUserRegistered = {
    val result: (Int, TwitterUserRegistered) = userRepository.getOrCreateTwitterUser(request, tempNextInternalID)

    tempNextInternalID = result._1
    metadataRepository.updateNextUserInternalID(tempNextInternalID)

    result._2
  }

  private def handleWykopRequest(request: RegisterWykopUser): WykopUserRegistered = {
    val result: (Int, WykopUserRegistered) = userRepository.getOrCreateWykopUser(request, tempNextInternalID)

    tempNextInternalID = result._1
    metadataRepository.updateNextUserInternalID(tempNextInternalID)

    result._2
  }
}
