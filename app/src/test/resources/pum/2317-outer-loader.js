/* Source: https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2317
 * Captured: 2026-07-26
 * Full response SHA-256: b3ee8b79cf1f77fce476fd95abb5597262b5c9455aa95a2e8e29faf73a186735
 */
(function(I){var d=document,w;if(!d.getElementById(I)){w=d.createElement("div");w.id=I;w.className="cloned_card";var wrap=d.createElement("div");wrap.className="cloned_notice loading_box big";var spin=d.createElement("div");spin.className="dc-spinner";for(var i=0;i<12;i++){spin.appendChild(d.createElement("span"));}wrap.appendChild(spin);w.appendChild(wrap);(d.querySelector("#pum_card")||d.querySelector("#container .write_div")).appendChild(w)}var u="https:\/\/gall.dcinside.com\/ajax\/pum_ajax\/get_contents";var data={"ci_t":null,"_GALLTYPE_":"","id":"laboratory1","no":2315};$.ajax({url:u,type:"POST",data:data,dataType:"html"}).done(function(h){if(!h)return;var $s=$("#"+I);if($s.length){$s.html(h)}});})("pum_container");
