package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class RemoteRequestGateContractTest {
 @Test fun everyDefaultHttpAttemptIncludingRedirectsUsesCallerGate() {
  val root=sequenceOf(File("."),File("..")).first {File(it,"src/main/java/com/example/armbandbot/RemoteListClient.kt").exists() || File(it,"app/src/main/java/com/example/armbandbot/RemoteListClient.kt").exists()}
  val source=sequenceOf(File(root,"src/main/java/com/example/armbandbot/RemoteListClient.kt"),File(root,"app/src/main/java/com/example/armbandbot/RemoteListClient.kt")).first{it.exists()}.readText().replace(Regex("\\s+"),"")
  assertTrue(source.contains("downloadRemoteLists(first.url,etag,beforeRequest)"))
  val attempt=source.substringAfter("repeat(2){")
  assertTrue(attempt.indexOf("beforeRequest()") in 0 until attempt.indexOf("c.responseCode"))
 }
}
