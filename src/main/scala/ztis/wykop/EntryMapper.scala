package ztis.wykop

import java.net.URI

import org.codehaus.jackson.JsonNode
import org.codehaus.jackson.map.ObjectMapper

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object EntryMapper {
  private val mapper = new ObjectMapper()
  
  def toEntry(json: String): Entry = {
    val node = mapper.readTree(json)  
    
    toEntry(node)
  }
  
  private def toEntry(node: JsonNode): Entry = {
    val author = node.get("author").asText()
    val link = URI.create(node.get("source_url").asText())
    
    Entry(author, link)
  }
  
  def toEntries(json: String): Vector[Entry] = {
    val nodes = mapper.readTree(json).getElements
    
    nodes.asScala.map(toEntry).toVector 
  }
  
  def toFollowers(json: String, buffer: ArrayBuffer[String]): ArrayBuffer[String] = {
    val nodes = mapper.readTree(json).getElements
    
    while (nodes.hasNext) {
      val node = nodes.next()
      val follower = toFollowersUserName(node)
      
      buffer += follower
    }
    
    buffer
  }

  private def toFollowersUserName(node: JsonNode): String = {
    val userName = node.get("login").asText()

    userName
  }
}
