"""Export actual retrieval features with explicitly catalog-derived relevance labels."""
import collections, concurrent.futures, hashlib, json, math, os, re, time, urllib.request
from pathlib import Path
import numpy as np
import pymysql

ROOT=Path(__file__).resolve().parents[1]
for file in [ROOT/'infra/.env',ROOT/'.env.local']:
    if file.exists():
        for line in file.read_text(encoding='utf-8-sig').splitlines():
            if re.match(r'^[A-Z][A-Z0-9_]*=',line):
                key,value=line.split('=',1);os.environ.setdefault(key,value.strip())
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(endpoint,payload):
    body=json.dumps(payload,ensure_ascii=False).encode()
    for attempt in range(3):
        try:
            request=urllib.request.Request(os.environ['SILICONFLOW_BASE_URL']+endpoint,data=body,headers={'Content-Type':'application/json','Authorization':'Bearer '+os.environ['SILICONFLOW_API_KEY']})
            with opener.open(request,timeout=75) as response:return json.load(response)
        except Exception:
            if attempt==2:raise RuntimeError('Model feature request failed; no credentials logged') from None
            time.sleep(2*(attempt+1))

def tokens(text):
    result=[]
    for word in re.findall(r'[a-z0-9]+|[\u4e00-\u9fff]+',text.lower()):
        if word.isascii():result.append(word)
        else:
            for i,char in enumerate(word):
                result.append(char)
                if i+1<len(word):result.append(word[i:i+2])
    return result

def main():
    products=json.loads((ROOT/'data/products.json').read_text(encoding='utf-8'))
    db=pymysql.connect(host='127.0.0.1',port=int(os.environ['MYSQL_PORT']),user=os.environ['MYSQL_USER'],password=os.environ['MYSQL_PASSWORD'],database=os.environ['MYSQL_DATABASE'],charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor,autocommit=True)
    deadline=time.time()+1200
    with db.cursor() as c:
        while True:
            c.execute('SELECT id,status,content_hash FROM knowledge_document');versions={r['id']:r for r in c.fetchall()}
            expected={p['id']:hashlib.sha256(b'qwen-token-boundaries-v2\n'+(ROOT/'data/manuals'/f"{p['sku']}.md").read_bytes()).hexdigest() for p in products}
            ready=sum(versions.get(i,{}).get('content_hash')==digest and versions[i]['status']=='READY' for i,digest in expected.items())
            if ready==200:break
            if time.time()>deadline:raise RuntimeError(f'Only {ready}/200 current documents ready')
            print(f'Waiting for tokenizer-v2 knowledge: {ready}/200',flush=True);time.sleep(15)
        c.execute('SELECT id,product_id,title,content,embedding_json FROM knowledge_chunk WHERE active=TRUE ORDER BY id');chunks=c.fetchall()
    db.close()
    documents=[r['title']+' '+r['content'] for r in chunks]
    counts=[collections.Counter(tokens(d)) for d in documents]
    lengths=np.array([sum(c.values()) for c in counts]);average=float(lengths.mean());df=collections.Counter(term for c in counts for term in c)
    vectors=np.array([json.loads(row['embedding_json']) for row in chunks],dtype=np.float32);vectors/=np.maximum(np.linalg.norm(vectors,axis=1,keepdims=True),1e-9)
    queries=[f"请说明{p['name']}（型号{p['sku']}）的规格、兼容要求及注意事项。" for p in products]
    cached=ROOT/'.local/ranking-query-vectors.json'
    if cached.exists():query_vectors=json.loads(cached.read_text())
    else:
        query_vectors=[]
        for start in range(0,len(queries),10):
            rows=api('/embeddings',{'model':os.environ['SILICONFLOW_EMBEDDING_MODEL'],'input':queries[start:start+10],'encoding_format':'float'})['data']
            query_vectors.extend(r['embedding'] for r in sorted(rows,key=lambda r:r['index']));print(f'Query embeddings {len(query_vectors)}/200',flush=True)
        cached.write_text(json.dumps(query_vectors),encoding='utf-8')
    product_map={p['id']:p for p in products}
    checkpoint=ROOT/'.local/ranking-query-results';checkpoint.mkdir(exist_ok=True)
    def one(index):
        p=products[index];query=queries[index];saved=checkpoint/f"{p['id']}.json"
        if saved.exists():return json.loads(saved.read_text(encoding='utf-8'))
        terms=set(tokens(query));bm=np.zeros(len(chunks))
        for term in terms:
            frequency=np.array([c.get(term,0) for c in counts]);idf=math.log(1+(len(chunks)-df.get(term,0)+.5)/(df.get(term,0)+.5))
            bm+=idf*frequency*2.2/(frequency+1.2*(.25+.75*lengths/average))
        v=np.array(query_vectors[index],dtype=np.float32);v/=max(float(np.linalg.norm(v)),1e-9);cos=vectors@v
        pool=[i for i,r in enumerate(chunks) if product_map[r['product_id']]['category']==p['category']]
        dense=sorted(pool,key=lambda i:-cos[i])[:10];sparse=sorted(pool,key=lambda i:-bm[i])[:10];candidates=list(dict.fromkeys(dense+sparse))
        scores=api('/rerank',{'model':os.environ['SILICONFLOW_RERANK_MODEL'],'query':query,'documents':[documents[i] for i in candidates],'top_n':len(candidates),'return_documents':False})['results']
        rerank={r['index']:r['relevance_score'] for r in scores};rows=[]
        for position,i in enumerate(candidates):
            c=chunks[i];family=c['title'].split(' · ')[0];target_family=p['name'].split(' · ')[0]
            grade=3 if c['product_id']==p['id'] else (1 if family==target_family else 0)
            rows.append({'queryId':f"catalog-{p['id']:04d}",'query':query,'productGroup':p['category'],'candidateId':str(c['id']),'productId':c['product_id'],'features':[float(bm[i]),float(cos[i]) if i in dense else 0.0,float(rerank[position]),float(family in query)],'label':grade,'labelSource':'catalog-target','featureSource':'retrieval'})
        saved.write_text(json.dumps(rows,ensure_ascii=False),encoding='utf-8');return rows
    all_rows=[];skipped=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        pending={executor.submit(one,i):i for i in range(len(products))}
        for done,future in enumerate(concurrent.futures.as_completed(pending),1):
            rows=future.result()
            if len({r['label'] for r in rows})<2:skipped.append(rows[0]['queryId'])
            else:all_rows.extend(rows)
            print(f'Real retrieval candidates {done}/200; rows={len(all_rows)}',flush=True)
    out=ROOT/'data/ranking/real-candidates.jsonl';out.write_text('\n'.join(json.dumps(r,ensure_ascii=False) for r in sorted(all_rows,key=lambda r:(r['queryId'],r['candidateId'])))+'\n',encoding='utf-8',newline='\n')
    (ROOT/'data/ranking/export-report.json').write_text(json.dumps({'queriesRequested':200,'queriesExported':200-len(skipped),'excludedQueriesWithoutRankingSignal':skipped,'rows':len(all_rows),'embeddingModel':os.environ['SILICONFLOW_EMBEDDING_MODEL'],'rerankModel':os.environ['SILICONFLOW_RERANK_MODEL'],'labelSource':'catalog-target','featureSource':'actual-model-and-corpus','candidatePool':'one category per query to keep disjoint evaluation products','limitations':'Automatic target-product labels; not human open-ended recommendation evaluation.'},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Real feature export complete.',flush=True)

if __name__=='__main__':main()
