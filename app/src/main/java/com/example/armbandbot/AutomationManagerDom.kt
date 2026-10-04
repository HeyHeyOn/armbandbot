package com.heyheyon.armbandbot
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** DC sends manager list/view controls in allowlisted templates. Never execute scripts. */
internal fun automationManagerFragments(document:Document):List<Document> =
 listOf(document) + document.select("script#minor_manager_view_buttons-tmpl,script#mini_manager_view_buttons-tmpl,script#minor_buttons-tmpl,script#mini_buttons-tmpl")
  .map { Jsoup.parseBodyFragment(it.data(),document.baseUri()) }

internal fun automationTabs(document:Document):List<GalleryCategory> =
 parseGalleryCategories(document.outerHtml()).filter { it.label !in setOf("공지","개념","추천") }

internal fun automationHasBumpControl(document:Document):Boolean =
 automationManagerFragments(document).any { it.select("[onclick*=update_bump]").isNotEmpty() }
