"""Convert the Apache-2.0 Qwen tokenizer vocabulary into JTokkit byte ranks."""
import base64, gzip, hashlib, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
source=ROOT/'.local/qwen3-tokenizer.json'
doc=json.loads(source.read_text(encoding='utf-8'))
visible=list(range(33,127))+list(range(161,173))+list(range(174,256))
mapping={chr(n):n for n in visible}
extra=0
for b in range(256):
    if b not in visible:
        mapping[chr(256+extra)]=b; extra+=1
target=ROOT/'backend/ai-service/src/main/resources/tokenizer'
target.mkdir(parents=True,exist_ok=True)
pattern=doc['pre_tokenizer']['pretokenizers'][0]['pattern']['Regex']
rows=[pattern]
for token,rank in sorted(doc['model']['vocab'].items(),key=lambda v:v[1]):
    decoded=bytes(mapping[c] for c in token)
    rows.append(base64.b64encode(decoded).decode()+' '+str(rank))
with open(target/'qwen3.tiktoken.gz','wb') as output:
    with gzip.GzipFile(fileobj=output,mode='wb',mtime=0) as compressed:compressed.write(('\n'.join(rows)+'\n').encode())
(ROOT/'data/tokenizer/source.json').write_text(json.dumps({'model':'Qwen/Qwen3-30B-A3B-Instruct-2507','source':'https://modelscope.cn/models/Qwen/Qwen3-30B-A3B-Instruct-2507/resolve/master/tokenizer.json','upstream':'https://huggingface.co/Qwen/Qwen3-30B-A3B-Instruct-2507','sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'license':'Apache-2.0','vocabularySize':len(doc['model']['vocab'])},ensure_ascii=False,indent=2),encoding='utf-8')
print('Prepared Qwen tokenizer ranks; source fingerprint recorded.')
