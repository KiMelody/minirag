#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清洗三部劳动法律官方原文 HTML/docx，生成 minirag 语料 JSON。

来源（均为官方公开文本）：
- 劳动法(2018二次修正)        中国人大网 t20190521_296651
- 劳动合同法(2012修正)        国家法律法规数据库导出 docx
- 劳动合同法实施条例(2008)    中国政府网 content_1099470
"""
import json
import re
from pathlib import Path

HERE = Path(__file__).parent
RAW = HERE / "raw"
ARTICLE = re.compile(r"第[一二三四五六七八九十百零]+条")


def html_text(path):
    h = path.read_text(encoding="utf-8", errors="ignore")
    h = re.sub(r"<script[\s\S]*?</script>|<style[\s\S]*?</style>", "", h)
    t = re.sub(r"<[^>]+>", " ", h)
    t = re.sub(r"[ \t\u3000]+", " ", t)
    return re.sub(r"\s*\n\s*", "\n", t).strip()


def clip(text, start, end):
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"找不到起点锚: {start[:30]}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"找不到终点锚: {end[:30]}")
    return text[i:j].strip()


def norm(text):
    # 条与条之间换行，便于阅读和人工核对；合并空白
    text = re.sub(r"\s*第([一二三四五六七八九十百零]+)条\s*", r"\n第\1条 ", text)
    text = text.replace("&lt;", "《").replace("&gt;", "》").replace("&amp;", "&").replace("&#160;", "")
    text = re.sub(r"[\u3000]+", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{2,}", "\n", text)
    return text.strip()


def build(doc_id, title, text):
    text = f"{title}\n{text}"
    out = RAW.parent / f"{doc_id}.json"
    out.write_text(json.dumps({"docId": doc_id, "text": text}, ensure_ascii=False), encoding="utf-8")
    n = len(ARTICLE.findall(text))
    print(f"{doc_id}: {len(text)} 字 / {n} 条 -> {out.name}")
    print(f"  头: {text[:80]}")
    print(f"  尾: {text[-80:]}")


# 劳动法：人大网页面，正文从修法信息起到页尾"编 辑："署名前
t = html_text(RAW / "labor-law.html")
t = clip(t, "（1994年", "编 辑：")
build("labor-law", "《中华人民共和国劳动法》（1994年通过，2009年、2018年两次修正）", norm(t))

# 劳动合同法：docx 提取的 txt，本身就是干净正文
t = (RAW / "labor-contract-law.txt").read_text(encoding="utf-8-sig")
build("labor-contract-law", "《中华人民共和国劳动合同法》（2007年通过，2012年修正）", norm(t))

# 实施条例：gov.cn 页面，正文从国务院令到页脚 E-mail 推荐前
t = html_text(RAW / "lc-impl-reg.html")
t = clip(t, "中华人民共和国国务院令", "【E-mail推荐")
build("lc-impl-reg", "《中华人民共和国劳动合同法实施条例》（国务院令第535号，2008年）", norm(t))
