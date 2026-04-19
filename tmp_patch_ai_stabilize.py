from pathlib import Path

path = Path(r'C:\Users\Administrator\.openclaw\workspace\projects\armbandbot\app\src\main\java\com\example\armbandbot\BotService.kt')
text = path.read_text(encoding='utf-8')
start = text.index('                    val immediatePostExecutions = aiBatchEvaluation.postDecisions.filter {')
end = text.index('                    val batchPostDecision = resultCache.remove(postNumStr)')
replacement = (
    '                    // 임시 안정화: 현재 글 외 다른 글/댓글에 대한 AI 즉시집행은 비활성화합니다.\n'
    '                    // AI 개별차단 추가 시점과 함께 도입된 배치 즉시집행 경로를 제거하고,\n'
    '                    // 현재 글 중심의 기존 안정 흐름만 유지합니다.\n\n'
)
path.write_text(text[:start] + replacement + text[end:], encoding='utf-8')
print('patched', start, end)
