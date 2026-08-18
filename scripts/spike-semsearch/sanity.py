"""Проверка самого конвейера, а не корпуса: если токенизация или пулинг сломаны,
цифры выше будут «плохими, но правдоподобными», и вывод окажется ложным."""
import pathlib, numpy as np, onnxruntime as ort, sentencepiece as spm
HERE = pathlib.Path(__file__).parent
sp = spm.SentencePieceProcessor(model_file=str(HERE/"spm.model"))
sess = ort.InferenceSession(str(HERE/"e5-int8.onnx"), providers=["CPUExecutionProvider"])
def enc(texts):
    ids=[[0]+[p+1 for p in sp.encode("query: "+t)][:510]+[2] for t in texts]
    w=max(map(len,ids)); inp=np.ones((len(ids),w),np.int64); m=np.zeros((len(ids),w),np.int64)
    for i,r in enumerate(ids): inp[i,:len(r)]=r; m[i,:len(r)]=1
    o=sess.run(None,{"input_ids":inp,"attention_mask":m,"token_type_ids":np.zeros_like(inp)})[0]
    mm=m[...,None].astype(np.float32); p=(o*mm).sum(1)/mm.sum(1)
    return p/np.linalg.norm(p,axis=1,keepdims=True)

print("токенизация «привет мир»:", sp.encode("query: привет мир"))
print("decode обратно:", sp.decode(sp.encode("query: привет мир")))

probe = [
 ("кот сидит на столе", "кошка лежит на столе", "экономика германии выросла на два процента"),
 ("напомни купить молоко", "не забыть взять молока в магазине", "лего про космос шатлы и ракеты"),
 ("write me a reminder about milk", "напомни про молоко", "стоимость семиместного автомобиля"),
]
for a,b,c in probe:
    v = enc([a,b,c])
    print(f"близкие {v[0]@v[1]:.3f}   далёкие {v[0]@v[2]:.3f}   «{a[:28]}»")
