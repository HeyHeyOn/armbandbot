package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
class SheetsGidPreservationTest {
 @Test fun malformedOrConflictingSourceGidIsNeverSilentlyReplaced() {
  for(suffix in listOf("?gid=bad","?gid=","?gid=12#gid=13","?gid=bad#gid=12")) {
   try {resolveRemoteUrl("https://docs.google.com/spreadsheets/d/owned_fixture/edit"+suffix,RemoteSourceKind.SHEETS);fail("invalid source gid accepted: $suffix")}catch(_:IllegalArgumentException){}
  }
  assertTrue(resolveRemoteUrl("https://docs.google.com/spreadsheets/d/owned_fixture/edit#gid=0012",RemoteSourceKind.SHEETS).endsWith("gid=0012"))
 }
}
