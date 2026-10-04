package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
class RemoteLocalAliasTest {
 @Test fun localStorageKeysMustResolveToRemoteKindWithoutOverwritingLocal() {
  val remote=RemoteLists(mapOf("normal" to listOf("shared"),"bypass" to listOf("bypass-shared"),"user_blacklist" to listOf("user-shared")))
  assertEquals(listOf("local","shared"),mergeRemoteList(listOf("local"),remote,"banned_normal"))
  assertEquals(listOf("local","bypass-shared"),mergeRemoteList(listOf("local"),remote,"banned_bypass"))
  assertEquals(listOf("local","user-shared"),mergeRemoteList(listOf("local"),remote,"user_blacklist"))
  assertEquals(listOf("local"),mergeRemoteList(listOf("local"),remote,"user_whitelist"))
 }
}
