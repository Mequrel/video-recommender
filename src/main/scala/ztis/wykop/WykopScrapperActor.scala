package ztis.wykop

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import ztis.VideoOrigin
import ztis.cassandra.CassandraClient
import ztis.user_video_service.VideoServiceActor.Video
import ztis.wykop.WykopScrapperActor.ScrapWykop

import scala.concurrent.duration._

object WykopScrapperActor {

  case object ScrapWykop

  def props(api: WykopAPI,
            cassandraClient: CassandraClient,
            userServiceActor: ActorRef,
            videoServiceActor: ActorRef): Props = {
    Props(classOf[WykopScrapperActor], api, cassandraClient, userServiceActor, videoServiceActor)
  }
}

class WykopScrapperActor(api: WykopAPI,
                         cassandraClient: CassandraClient,
                         userServiceActor: ActorRef,
                         videoServiceActor: ActorRef) extends Actor with ActorLogging {

  private val durationBetweenScrappings = context.system.settings.config.getInt("wykop.seconds-between-requests").seconds

  private val timeoutDuration = context.system.settings.config.getInt("wykop.entry-timeout-seconds").seconds
  
  self ! ScrapWykop

  override def receive: Receive = LoggingReceive {
    case ScrapWykop => {
      try {
        val entries = api.mainPageEntries(1) ++ api.upcomingPageEntries(1)
        val videoEntries = entries.flatMap { entry =>
          val videoOrigin = VideoOrigin.recognize(entry.link.getHost)

          videoOrigin.map(origin => VideoEntry(entry.author, Video(origin, entry.link)))
        }

        if (videoEntries.nonEmpty) {
          log.info(s"Extracted video entries $videoEntries from $entries")
          videoEntries.foreach { entry =>
            context.actorOf(entryProcessorProps(entry))
          }
        }

        scheduleNextScrapping()
      } catch {
        case e: Exception => log.error(e, "Problems during wykop scrapping")
      }
    }
  }

  private def entryProcessorProps(entry: VideoEntry): Props = {
    VideoEntryProcessorActor.props(entry,
      timeoutDuration,
      cassandraClient, 
      userServiceActor = userServiceActor, 
      videoServiceActor = videoServiceActor)
  }
  
  private def scheduleNextScrapping(): Unit = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(durationBetweenScrappings, self, ScrapWykop)
  }
}
