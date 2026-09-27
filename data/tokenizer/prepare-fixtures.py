from pathlib import Path
import json
from tokenizers import Tokenizer
root=Path(__file__).resolve().parents[2]
tokenizer=Tokenizer.from_file(str(root/'.local/qwen3-tokenizer.json'))
samples=['中文','Hello, world!',"We're comparing USB-C chargers.",'预算300元，不要耳塞式耳机。','65W / 10000mAh / USB PD 3.0','😄🌿🔋','  多个   空格\n\n换行\r\n','é à 中文 English 123456','a\u00a0b\u3000c','甲'*700]
samples += [p.read_text(encoding='utf-8') for p in sorted((root/'data/manuals').glob('*.md'))[::20]]
rows=[{'text':text,'count':len(tokenizer.encode(text,add_special_tokens=False).ids)} for text in samples]
path=root/'backend/ai-service/src/test/resources/qwen-tokenizer-fixtures.json'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Wrote {len(rows)} fixtures from the upstream Hugging Face tokenizer implementation.')
