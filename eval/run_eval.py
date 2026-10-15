#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""minirag M3 检索评估：对比 纯向量 / 混合(余弦+BM25+RRF) / 混合+LLM重排 的命中率。

前置：应用已在 localhost:18090 运行，且 eval/corpus/*.json 已全部入库。
用法：python eval/run_eval.py
可选环境变量：EVAL_BASE / EVAL_DATASET / EVAL_DB / EVAL_REPORT / EVAL_TITLE（用于不同语料的复跑）
输出：eval/eval-report.md
"""
import json
import os
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

here = Path(__file__).parent
BASE = os.environ.get("EVAL_BASE", "http://localhost:18090")
DATASET = Path(os.environ.get("EVAL_DATASET", here / "dataset.json"))
DB = Path(os.environ.get("EVAL_DB", here.parent / "data" / "minirag.db"))
REPORT = Path(os.environ.get("EVAL_REPORT", here / "eval-report.md"))
TITLE = os.environ.get("EVAL_TITLE", "minirag M3 检索评估报告")
MODES = [("vector", "纯向量(余弦)"), ("hybrid", "混合(余弦+BM25→RRF)"), ("rerank", "混合+LLM重排")]


def search(q, mode):
    url = BASE + "/api/search?" + urllib.parse.urlencode({"q": q, "mode": mode})
    with urllib.request.urlopen(url, timeout=300) as r:
        return [item["docId"] for item in json.load(r)["results"]]


def main():
    here = Path(__file__).parent
    queries = json.loads(DATASET.read_text(encoding="utf-8"))["queries"]

    db = DB
    n_chunks = n_docs = "?"
    if db.exists():
        con = sqlite3.connect(str(db))
        n_chunks, = con.execute("SELECT count(*) FROM chunks").fetchone()
        n_docs, = con.execute("SELECT count(distinct doc_id) FROM chunks").fetchone()
        con.close()

    results = {m: {} for m, _ in MODES}
    for m, _ in MODES:
        for item in queries:
            t0 = time.time()
            results[m][item["q"]] = search(item["q"], m)
            print(f"[{m}] {item['q']} -> {results[m][item['q']]} ({time.time()-t0:.1f}s)", flush=True)

    def metrics(mode):
        top1 = rec3 = 0
        mrr = 0.0
        for item in queries:
            got = results[mode][item["q"]]
            rank = next((i + 1 for i, d in enumerate(got) if d == item["expect"]), None)
            top1 += 1 if rank == 1 else 0
            rec3 += 1 if rank else 0
            if rank:
                mrr += 1.0 / rank
        n = len(queries)
        return top1 / n, rec3 / n, mrr / n

    lines = [
        f"# {TITLE}", "",
        f"- 生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 语料: {n_docs} 份文档 / {n_chunks} 个切片（500 字窗口，80 字重叠）",
        f"- 查询集: {len(queries)} 条中文真实问法，文档级标注（含关键词精确型与语义改写型）",
        f"- Embedding: BAAI/bge-m3（硅基流动）；重排: deepseek/deepseek-v4.1-flash（中转站）",
        "- 局限：样本规模小（11 切片/15 查询），结论为方向性参考，不构成显著性差异", "",
        "| 模式 | Top1 准确率 | Recall@6 | MRR |",
        "|---|---|---|---|",
    ]
    for m, label in MODES:
        top1, rec, mrr = metrics(m)
        lines.append(f"| {label} | {top1:.1%} | {rec:.1%} | {mrr:.3f} |")

    lines += ["", "## 逐条明细（数字为期望文档在结果中的名次，✗ 表示前 6 未命中）", "",
              "| 查询 | 期望文档 | 纯向量 | 混合 | 混合+重排 |", "|---|---|---|---|---|"]
    for item in queries:
        cells = []
        for m, _ in MODES:
            got = results[m][item["q"]]
            rank = next((i + 1 for i, d in enumerate(got) if d == item["expect"]), None)
            cells.append(str(rank) if rank else "✗")
        lines.append(f"| {item['q']} | {item['expect']} | {cells[0]} | {cells[1]} | {cells[2]} |")

    out = REPORT
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n报告已写入 {out}")
    for m, label in MODES:
        top1, rec, mrr = metrics(m)
        print(f"{label}: Top1={top1:.1%} Recall@6={rec:.1%} MRR={mrr:.3f}")


if __name__ == "__main__":
    sys.exit(main())
