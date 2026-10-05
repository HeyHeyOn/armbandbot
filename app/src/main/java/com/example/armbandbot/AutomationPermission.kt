package com.heyheyon.armbandbot
import org.jsoup.nodes.Document

internal fun automationCiToken(document:Document,cookie:String):String =
 cookie.split(';').map{it.trim()}.firstOrNull{it.startsWith("ci_c=")}?.substringAfter('=')
  ?: document.select("input[name=ci_t]").attr("value")

/** A manager tab is optional; management controls can live inside a script template. */
internal fun automationManagerConfirmed(document:Document):Boolean {
 val managerTab=Regex("""listSearchHead\((?:999|'999'|"999")\)""")
 val managementPage=Regex("""location\.href\s*=\s*(['"])/(?:mgallery|mini)/management\?id=[A-Za-z0-9_-]+\1\s*;?""")
 if(document.select("a[onclick],a[href*=manager],button[onclick],.useradmin").any { e ->
   val onclick=e.attr("onclick")
   managerTab.containsMatchIn(onclick.replace(Regex("\\s+"),"")) ||
    (e.hasClass("btn_useradmin_go") && managementPage.matches(onclick.trim())) ||
    ((e.attr("href").contains("manager") || onclick.contains("manager") || e.hasClass("useradmin")) &&
       (e.text().contains("매니저") || e.text().contains("관리")))
  })return true
 val changeHeadtext=Regex("""javascript:\s*chg_headtext(?:_batch)?\(\s*\d+\s*\)\s*;?""")
 return automationManagerFragments(document).any { fragment ->
  fragment.select(".mng_subject_sel a[href]").any { changeHeadtext.matches(it.attr("href").trim()) }
 }
}
