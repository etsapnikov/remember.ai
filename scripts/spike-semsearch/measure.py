#!/usr/bin/env python3
"""Спайк Р-15.15: BM25 против multilingual-e5-small на живом корпусе.

Отчёт с числами — docs/spike-1_0_2-semsearch.md. Здесь только замер.

Ни модель, ни корпус в репозиторий не входят: первое весит 118 МБ, второе —
личные записи владельца. Пути передаются флагами:

    python3 measure.py --db <копия базы> --model <e5-int8.onnx> --spm <spm.model>
"""
import json, math, re, sqlite3, statistics, sys, time, pathlib
import numpy as np, onnxruntime as ort, sentencepiece as spm

HERE = pathlib.Path(__file__).parent


def arg(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


DB = arg("--db", HERE / "prinyal.db")
MODEL = arg("--model", HERE / "e5-int8.onnx")
SPM = arg("--spm", HERE / "spm.model")

rows = list(sqlite3.connect(DB).execute(
    "select transcript from notes where transcript is not null and transcript!='' order by created_at"))
docs = [r[0] for r in rows]
pairs = json.load(open(HERE / "pairs.json"))["pairs"]

# --- BM25 (свой, чтобы не тащить зависимость в проект) ---
def toks(text):
    words = re.findall(r"[а-яёa-z0-9]+", text.lower())
    # Русское словоизменение: рубим хвост, но не короче четырёх букв.
    return [w[:5] if len(w) > 6 else w for w in words]

class BM25:
    def __init__(self, corpus, k1=1.2, b=0.75):
        self.docs = [toks(d) for d in corpus]
        self.k1, self.b = k1, b
        self.avg = sum(map(len, self.docs)) / len(self.docs)
        self.df = {}
        for d in self.docs:
            for t in set(d):
                self.df[t] = self.df.get(t, 0) + 1
        self.N = len(self.docs)
    def scores(self, query):
        q = toks(query)
        out = np.zeros(self.N)
        for i, d in enumerate(self.docs):
            if not d: continue
            tf = {}
            for t in d: tf[t] = tf.get(t, 0) + 1
            s = 0.0
            for t in q:
                if t not in tf: continue
                idf = math.log(1 + (self.N - self.df[t] + 0.5) / (self.df[t] + 0.5))
                s += idf * tf[t] * (self.k1 + 1) / (tf[t] + self.k1 * (1 - self.b + self.b * len(d) / self.avg))
            out[i] = s
        return out

# --- e5 ---
sp = spm.SentencePieceProcessor(model_file=str(SPM))
sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
NAMES = {i.name for i in sess.get_inputs()}

def encode(texts, prefix="query: ", batch=8):
    vecs, lat = [], []
    for start in range(0, len(texts), batch):
        chunk = texts[start:start + batch]
        ids = [[0] + [p + 1 for p in sp.encode(prefix + t)][:510] + [2] for t in chunk]
        width = max(map(len, ids))
        inp = np.ones((len(ids), width), dtype=np.int64)  # <pad> = 1
        mask = np.zeros((len(ids), width), dtype=np.int64)
        for i, row in enumerate(ids):
            inp[i, :len(row)] = row
            mask[i, :len(row)] = 1
        feed = {"input_ids": inp, "attention_mask": mask}
        if "token_type_ids" in NAMES:
            feed["token_type_ids"] = np.zeros_like(inp)
        t0 = time.perf_counter()
        out = sess.run(None, feed)[0]
        lat.append((time.perf_counter() - t0) / len(chunk) * 1000)
        m = mask[..., None].astype(np.float32)
        pooled = (out * m).sum(1) / m.sum(1)          # mean pooling, как у e5
        vecs.append(pooled / np.linalg.norm(pooled, axis=1, keepdims=True))
    return np.vstack(vecs), lat

print(f"корпус: {len(docs)} заметок, пар: {len(pairs)}")
bm = BM25(docs)
t0 = time.perf_counter()
emb, lat = encode(docs)
print(f"эмбеддинги {len(docs)} шт: {time.perf_counter()-t0:.1f} c, "
      f"на заметку медиана {statistics.median(lat):.0f} мс (mac, int8, CPU)")

def evaluate(name, ranker):
    res = {}
    for kind in ("strong", "weak", "all"):
        got1 = got3 = got8 = tot = 0
        rr = []
        for a, b, strength, _ in pairs:
            if kind != "all" and strength != kind: continue
            for q, want in ((a, b), (b, a)):
                order = [i for i in np.argsort(-ranker(q)) if i != q]
                rank = order.index(want) + 1
                tot += 1
                got1 += rank <= 1; got3 += rank <= 3; got8 += rank <= 8
                rr.append(1 / rank)
        res[kind] = (tot, got1 / tot, got3 / tot, got8 / tot, statistics.mean(rr))
    print(f"\n{name}")
    for kind, (tot, r1, r3, r8, mrr) in res.items():
        print(f"  {kind:6} запросов {tot:3}  R@1 {r1:.2f}  R@3 {r3:.2f}  R@8 {r8:.2f}  MRR {mrr:.3f}")
    return res

bm_res = evaluate("BM25", lambda i: bm.scores(docs[i]))
e5_res = evaluate("e5-small int8", lambda i: emb @ emb[i])

hybrid = evaluate("гибрид (сумма рангов)", lambda i: (
    -np.argsort(np.argsort(-bm.scores(docs[i]))).astype(float)
    - np.argsort(np.argsort(-(emb @ emb[i]))).astype(float)))

json.dump({"bm25": bm_res, "e5": e5_res, "hybrid": hybrid,
           "latency_ms_median_mac": statistics.median(lat), "docs": len(docs)},
          open(HERE / "result.json", "w"), ensure_ascii=False, indent=2)
