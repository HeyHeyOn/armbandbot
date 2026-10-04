package com.heyheyon.armbandbot
import org.jsoup.nodes.Document

internal fun automationCiToken(document:Document,cookie:String):String =
 cookie.split(';').map{it.trim()}.firstOrNull{it.startsWith("ci_c=")}?.substringAfter('=')
  ?: document.select("input[name=ci_t]").attr("value")

internal fun automationManagerConfirmed(document:Document):Boolean =
 document.select("a[onclick],a[href*=manager],button[onclick*=manager],.useradmin").any { e ->
  val onclick=e.attr("onclick").replace(" ","")
  onclick.contains("listSearchHead(999)") ||
   ((e.attr("href").contains("manager") || onclick.contains("manager") || e.hasClass("useradmin")) && (e.text().contains("매니저") || e.text().contains("관리")))
 }
