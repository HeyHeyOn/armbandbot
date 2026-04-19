from pathlib import Path
path = Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\app\src\main\java\com\example\armbandbot\BotService.kt')
text = path.read_text(encoding='utf-8')
repls = {
'''                if (config.isYudongPostBlock) {
                    blockReasonPrefix = "유동 게시글 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 감지"
                } else if (config.isYudongImageBlock && postImageAlts.isNotEmpty()) {
                    blockReasonPrefix = "유동 이미지 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 이미지 첨부 감지"
                } else if (
                    config.isYudongVoiceBlock &&
                    (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 보이스 첨부 감지"
                }
''': '''                if (config.isYudongPostBlock) {
                    blockReasonPrefix = "유동 게시글 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 감지"
                    filterSource = ModerationFilterSource.YUDONG
                } else if (config.isYudongImageBlock && postImageAlts.isNotEmpty()) {
                    blockReasonPrefix = "유동 이미지 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 이미지 첨부 감지"
                    filterSource = ModerationFilterSource.YUDONG
                } else if (
                    config.isYudongVoiceBlock &&
                    (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 작성자 + 보이스 첨부 감지"
                    filterSource = ModerationFilterSource.YUDONG
                }
''',
'''                    if (config.isKkangPostBlock) {
                        blockReasonPrefix = "깡계 게시글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (config.isKkangImageBlock && postImageAlts.isNotEmpty()) {
                        blockReasonPrefix = "깡계 이미지 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 이미지 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (
                        config.isKkangVoiceBlock &&
                        (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    }
''': '''                    if (config.isKkangPostBlock) {
                        blockReasonPrefix = "깡계 게시글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                        filterSource = ModerationFilterSource.KKANG
                    } else if (config.isKkangImageBlock && postImageAlts.isNotEmpty()) {
                        blockReasonPrefix = "깡계 이미지 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 이미지 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                        filterSource = ModerationFilterSource.KKANG
                    } else if (
                        config.isKkangVoiceBlock &&
                        (postRawHtml.contains("btn-voice") || postRawHtml.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                        filterSource = ModerationFilterSource.KKANG
                    }
''',
'''                if (config.isYudongCommentBlock) {
                    blockReasonPrefix = "유동 댓글 작성 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 감지"
                } else if (
                    config.isYudongVoiceBlock &&
                    (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 + 보이스 첨부 감지"
                }
''': '''                if (config.isYudongCommentBlock) {
                    blockReasonPrefix = "유동 댓글 작성 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 감지"
                    filterSource = ModerationFilterSource.YUDONG
                } else if (
                    config.isYudongVoiceBlock &&
                    (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                ) {
                    blockReasonPrefix = "유동 보이스 첨부 금지"
                    notiType = "yudong"
                    debugDetail = "유동 댓글 작성자 + 보이스 첨부 감지"
                    filterSource = ModerationFilterSource.YUDONG
                }
''',
'''                    if (config.isKkangCommentBlock) {
                        blockReasonPrefix = "깡계 댓글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 댓글 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    } else if (
                        config.isKkangVoiceBlock &&
                        (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 댓글 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                    }
''': '''                    if (config.isKkangCommentBlock) {
                        blockReasonPrefix = "깡계 댓글 금지(글:$pCount/댓:$cCount)"
                        notiType = "kkang"
                        debugDetail = "깡계 댓글 기준 미달: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                        filterSource = ModerationFilterSource.KKANG
                    } else if (
                        config.isKkangVoiceBlock &&
                        (commentMemo.contains("voice_wrap") || commentMemo.contains("voice/player"))
                    ) {
                        blockReasonPrefix = "깡계 보이스 첨부 금지"
                        notiType = "kkang"
                        debugDetail = "깡계 기준 미달 + 댓글 보이스 첨부: 글=$pCount/${config.kkangPostMin}, 댓글=$cCount/${config.kkangCommentMin}"
                        filterSource = ModerationFilterSource.KKANG
                    }
'''
}
for old, new in repls.items():
    if old not in text:
        raise SystemExit('target block not found')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('patched')
