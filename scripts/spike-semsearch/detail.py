"""Почему цифры такие: распределение похожестей и пары, где методы расходятся."""
import json, pathlib, sqlite3, statistics, numpy as np, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from measure import bm, docs, emb, pairs   # переиспользуем конвейер

sims = emb @ emb.T
off = sims[~np.eye(len(docs), dtype=bool)]
print(f"похожесть между случайными заметками корпуса: медиана {np.median(off):.3f}, "
      f"90-й перцентиль {np.percentile(off,90):.3f}, максимум {off.max():.3f}")

print("\nпара                                  BM25  e5   общих редких слов")
def toksof(i):
    from measure import toks
    return set(toks(docs[i]))
for a,b,strength,label in pairs:
    ra = [i for i in np.argsort(-bm.scores(docs[a])) if i!=a].index(b)+1
    re_ = [i for i in np.argsort(-(emb@emb[a])) if i!=a].index(b)+1
    shared = toksof(a) & toksof(b)
    rare = [t for t in shared if bm.df.get(t,0) <= 3]
    print(f"{strength:6} {label[:34]:34} {ra:4} {re_:4}   {len(rare)} {sorted(rare)[:3]}")
