package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test

class RemoteListsTest {
 @Test fun structuredJsonAcceptsAllowlistedArraysAndPreservesOrder() {
  val v=parseRemoteLists("""{"version":1,"lists":{"normal":["a","b","a"],"user_blacklist":["id1"]}}""")
  assertEquals(listOf("a","b"),v.values["normal"]);assertEquals(listOf("id1"),v.values["user_blacklist"])
 }
 @Test fun csvSupportsQuotedCommasNewlinesAndUtf8() {
  val v=parseRemoteLists("\uFEFFtype,value\r\nnormal,광고\r\nnickname_blacklist,\"이름,하나\"\r\nbypass,\"두\n줄\"\r\n")
  assertEquals(listOf("이름,하나"),v.values["nickname_blacklist"])
  assertEquals(listOf("두\n줄"),v.values["bypass"])
 }
 @Test fun localValuesRemainAndEmptyRemoteDoesNotEraseThem() {
  val v=parseRemoteLists("""{"version":1,"lists":{"normal":[]}}""")
  assertEquals(listOf("local"),mergeRemoteList(listOf("local"),v,"normal"))
 }
 @Test fun mergeIsStableAndDeduplicated() { assertEquals(listOf("l","r"),mergeRemoteList(listOf("l"),parseRemoteLists("type,value\nnormal,l\nnormal,r"),"normal")) }
 @Test(expected=IllegalArgumentException::class) fun htmlLoginPageRejected() { parseRemoteLists("<html>Login</html>") }
 @Test(expected=IllegalArgumentException::class) fun unknownCsvTypeRejected() { parseRemoteLists("type,value\nuser_whitelist,*") }
 @Test(expected=IllegalArgumentException::class) fun wrongJsonSchemaRejected() { parseRemoteLists("""{"version":2,"lists":{"normal":["a"]}}""") }
 @Test(expected=IllegalArgumentException::class) fun wrongJsonValueTypeRejected() { parseRemoteLists("""{"version":1,"lists":{"normal":"abc"}}""") }
 @Test(expected=IllegalArgumentException::class) fun unterminatedCsvRejected() { parseRemoteLists("type,value\nnormal,\"broken") }
 @Test(expected=IllegalArgumentException::class) fun noHeaderCsvRejected() { parseRemoteLists("normal,value") }
 @Test fun sheetsEditUrlConvertsToCsvAndKeepsSelectedGid() {
  assertEquals("https://docs.google.com/spreadsheets/d/abc_123/export?format=csv&gid=42",resolveRemoteUrl("https://docs.google.com/spreadsheets/d/abc_123/edit#gid=42",RemoteSourceKind.SHEETS))
 }
 @Test fun publishedSheetsCsvIsAccepted() { assertEquals("https://docs.google.com/spreadsheets/d/e/abc123/pub?output=csv",resolveRemoteUrl("https://docs.google.com/spreadsheets/d/e/abc123/pub?output=csv",RemoteSourceKind.SHEETS)) }
 @Test fun rawGithubIsAccepted() { assertEquals("https://raw.githubusercontent.com/a/b/main/list.json",resolveRemoteUrl("https://raw.githubusercontent.com/a/b/main/list.json",RemoteSourceKind.GITHUB)) }
 @Test(expected=IllegalArgumentException::class) fun privateIpAndPlainHttpRejected() { resolveRemoteUrl("http://127.0.0.1/list",RemoteSourceKind.JSON) }
 @Test(expected=IllegalArgumentException::class) fun credentialsInUrlRejected() { resolveRemoteUrl("https://user:pw@raw.githubusercontent.com/a/b/main/list.json",RemoteSourceKind.GITHUB) }
 @Test(expected=IllegalArgumentException::class) fun unsafeGenericHostRejected() { resolveRemoteUrl("https://localhost/list",RemoteSourceKind.JSON) }
 @Test fun revisionIsIndependentOfJsonFormatting() {
  val a=parseRemoteLists("""{"version":1,"lists":{"normal":["a"]}}""");val b=parseRemoteLists("type,value\nnormal,a")
  assertEquals(a.revision,b.revision)
 }
}
