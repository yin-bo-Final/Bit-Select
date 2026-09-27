"""Reproducible original demo catalogue; no external or licensed product claims."""
import json
from pathlib import Path
from html import escape

ROOT = Path(__file__).resolve().parents[1]
GROUPS = [
    ('digital', '数码配件', 40, ['氮化镓充电器','编织充电线','桌面扩展坞','轻薄移动电源','磁吸手机支架','无线充电座','读卡器','旅行转换插座','桌面理线器','数码收纳包'], 3900),
    ('office', '电脑与办公', 35, ['无线键盘','静音鼠标','电脑支架','办公台灯','桌面收纳架','显示器支架','护腕鼠标垫'], 6900),
    ('audio', '音频与智能', 25, ['无线耳机','桌面蓝牙音箱','开放式耳机','智能插座','温湿度计'], 9900),
    ('appliances', '生活小家电', 35, ['桌面循环风扇','冷雾加湿器','手持吸尘器','电动牙刷','便携吹风机','衣物毛球修剪器','除螨清洁机'], 8900),
    ('kitchen', '厨房与餐饮', 35, ['不锈钢保温杯','轻量炒锅','耐热玻璃饭盒','餐具套装','厨房收纳架','手冲咖啡壶','切菜板'], 4900),
    ('living', '家居与出行', 30, ['旅行双肩包','折叠收纳箱','记忆棉枕','轻便雨伞','速干浴巾','旅行洗漱包'], 5900),
]
COLORS = ['#303b48', '#597764', '#8193a3', '#b39177', '#696a89']
MODELS = ['轻享', '悦用', '舒适', '进阶', '旗舰']

def specs_for(name, variant):
    s = {'设计系列': MODELS[variant], '颜色': ['石墨','松绿','雾蓝','沙棕','暮紫'][variant], '数据说明': '原创演示规格，不对应真实商品认证'}
    if '充电器' in name: s.update({'额定输出': f'{[20,30,45,65,100][variant]}W', '接口': 'USB-C', '输入': '100-240V AC', '快充协议': 'USB PD', '兼容说明': '设备与线缆均支持相同协议才可快充'})
    elif '充电线' in name: s.update({'长度': f'{[1,1.2,1.5,2,3][variant]}m', '接口': 'USB-C 至 USB-C', '额定电流': '3A', '传输速率': 'USB 2.0 480Mbps'})
    elif '移动电源' in name: s.update({'标称容量': f'{[5000,10000,12000,15000,20000][variant]}mAh', '电芯标称电压': '3.7V', '额定输出': '20W', '接口': 'USB-C', '限制': '不可进水，不支持笔记本高功率供电'})
    elif '扩展坞' in name: s.update({'接口': 'USB-C主机 / USB-A×2 / HDMI×1', '视频输出': '最高4K 30Hz', '兼容说明': '主机USB-C需支持DP Alt Mode', '线长': '20cm'})
    elif '耳机' in name or '音箱' in name: s.update({'连接': '蓝牙5.3', '充电': 'USB-C 5V', '参考续航': f'{[6,8,10,12,16][variant]}小时（中等音量演示条件）', '降噪': '不支持主动降噪', '防水': '无防水等级声明'})
    elif '风扇' in name: s.update({'额定功率': f'{[5,8,12,18,25][variant]}W', '供电': '配套低压电源', '档位': '3档', '尺寸': f'{[15,18,20,25,30][variant]}cm', '噪音': '未提供检测值，请勿据此承诺静音分贝'})
    elif '加湿器' in name: s.update({'水箱容量': f'{[1,1.5,2,3,4][variant]}L', '额定功率': '18W', '方式': '冷雾', '适用': '日常小空间加湿', '限制': '仅加洁净水，不加精油或消毒剂'})
    elif '保温杯' in name or '咖啡壶' in name: s.update({'容量': f'{[350,450,500,650,800][variant]}mL', '主体材质': '304不锈钢', '清洁': '温水手洗', '限制': '不放入微波炉'})
    elif '炒锅' in name: s.update({'直径': f'{[24,26,28,30,32][variant]}cm', '主体材质': '不锈钢', '适用热源': '燃气灶、电磁炉', '注意': '锅柄可能升温，请使用隔热手套'})
    elif '背包' in name or '双肩包' in name: s.update({'容量': f'{[12,16,20,24,28][variant]}L', '面料': '聚酯纤维', '清洁': '局部轻柔擦拭', '限制': '不作为户外防水或专业承重装备'})
    elif '键盘' in name: s.update({'布局': '84键', '连接': '蓝牙 / USB-C', '系统': 'Windows、macOS', '供电': 'USB-C 5V', '轴体': '剪刀脚薄膜结构'})
    elif '鼠标' in name: s.update({'连接': '2.4GHz接收器', '灵敏度': f'{[800,1200,1600,2400,3200][variant]}DPI', '系统': 'Windows、macOS', '供电': 'AA电池1节（需另购）'})
    elif '灯' in name: s.update({'额定功率': f'{[5,6,8,10,12][variant]}W', '调光': '3档', '色温': '4000K', '供电': '配套电源', '限制': '仅供室内干燥环境使用'})
    else: s.update({'尺寸规格': ['小号','标准号','中号','大号','加大号'][variant], '使用场景': '日常家庭与办公', '维护': '断开电源或清空后，以柔软布清洁', '限制': '具体适配信息未列出时应咨询导购，不推断认证和极限性能'})
    return s

def shape(category, color):
    # Original vector product studies, intentionally labelled as demo illustrations.
    if category == 'digital': return f'<rect x="170" y="90" width="172" height="205" rx="32" fill="{color}"/><path d="M210 92V58M299 92V58" stroke="#899198" stroke-width="15"/><rect x="221" y="168" width="70" height="18" rx="9" fill="#171e26"/><path d="M222 242h68" stroke="#ffffff" stroke-opacity=".3" stroke-width="3"/>'
    if category == 'office': return f'<path d="M114 250L189 112h163l46 138z" fill="{color}"/><path d="M166 219h196" stroke="#cad3dc" stroke-width="9"/><rect x="99" y="252" width="312" height="27" rx="13" fill="#313c48"/>'
    if category == 'audio': return f'<path d="M160 205v-48a96 96 0 0 1 192 0v48" fill="none" stroke="{color}" stroke-width="30"/><rect x="131" y="160" width="58" height="125" rx="28" fill="{color}"/><rect x="323" y="160" width="58" height="125" rx="28" fill="{color}"/>'
    if category == 'appliances': return f'<rect x="239" y="214" width="34" height="76" fill="{color}"/><ellipse cx="256" cy="299" rx="86" ry="16" fill="{color}"/><circle cx="256" cy="163" r="98" fill="{color}"/><circle cx="256" cy="163" r="78" fill="#e5e9e8"/><path d="M256 163C163 130 210 68 256 163C285 62 352 123 256 163C339 206 274 263 256 163" fill="{color}"/><circle cx="256" cy="163" r="16" fill="#d6deda"/>'
    if category == 'kitchen': return f'<rect x="186" y="102" width="140" height="205" rx="31" fill="{color}"/><rect x="182" y="80" width="148" height="47" rx="18" fill="#343d43"/><path d="M210 148v111" stroke="white" stroke-opacity=".24" stroke-width="12" stroke-linecap="round"/>'
    return f'<path d="M203 96v-8a53 53 0 0 1 106 0v8" fill="none" stroke="{color}" stroke-width="14"/><rect x="155" y="85" width="202" height="237" rx="45" fill="{color}"/><rect x="179" y="201" width="154" height="83" rx="20" fill="white" fill-opacity=".14"/><path d="M188 219h135" stroke="#273039" stroke-width="5"/>'

def main():
    products=[]
    assets=ROOT/'web/public/products'; assets.mkdir(parents=True,exist_ok=True)
    manuals=ROOT/'data/manuals'; manuals.mkdir(parents=True,exist_ok=True)
    public_manuals=ROOT/'web/public/manuals'; public_manuals.mkdir(parents=True,exist_ok=True)
    for category,label,count,names,base in GROUPS:
        for i in range(count):
            variant=i//len(names); name=names[i%len(names)]; ident=len(products)+1; sku=f'BS-{ident:04d}'
            title=f'{name} · {MODELS[variant]} {sku[-3:]}'
            specifications=specs_for(name,variant)
            price=base+variant*2500+(i%len(names))*700
            desc=f'为日常{label}场景设计，{MODELS[variant]}系列，关注实用细节与清晰参数。原创演示商品。'
            p=dict(id=ident,sku=sku,name=title,category=category,categoryName=label,description=desc,priceCents=price,originalPriceCents=price,imageUrl=f'/products/{sku}.svg',stock=20+i%31,sold=0,featured=i<2,enabled=True,specifications=specifications,manualUrl=f'/manuals/{sku}.html',tags=[label,MODELS[variant],'演示商品'])
            products.append(p)
            table='\n'.join(f'- {k}：{v}' for k,v in specifications.items())
            text=f'''# {title} 使用说明书

文档编号：MAN-{sku}；商品编号：{ident}；版本：1.0；日期：2026-09-27。
来源：比特严选原创演示资料。本说明不对应真实制造商产品，不构成认证或实际安全性能声明。

## 产品概述
{desc} 型号为 {sku}。仅适用于说明中明确列出的用途；未列出的功能视为未知。

## 规格与兼容性
{table}
请先对照型号与接口。接口外形一致不代表功能或协议完全兼容；存在疑问时先咨询，不强行连接。

## 包装清单
{name}主体一件，说明书一份。电池、线缆、适配器等附件仅以商品规格明确列出的内容为准，未说明的附件不默认包含。

## 首次使用
1. 核对型号 {sku}，检查外观及包装是否完整，有破损时停止使用并联系平台。
2. 阅读规格及限制条件，清除不属于产品的包装物，置于合适的平稳环境。
3. 如属于电器，仅使用匹配规格的供电；如属于容器或织物，先按对应清洁要求处理。
4. 首次短时试用并观察，确认正常后再持续使用。儿童使用需要成年人指导。

## 维护与保养
清洁前断电或清空内容物。使用柔软布轻柔清理，不使用强腐蚀清洁剂，不自行拆开电气部件。
清洁方式以本型号规格为准；未注明可机洗、可浸泡或可微波加热的，均不作此承诺。
长期存放前保持干燥，避免高温、潮湿、挤压和阳光持续直射。

## 注意事项
使用环境与适配要求见上方规格。出现异味、异常发热、漏液或结构损坏时停止使用；不要尝试带故障继续运行。
本演示资料不包含未验证的防水等级、食品接触认证、医疗效果、噪声检测和极限承重指标。

## 常见问题
问：{title} 是否适合我的设备或空间？
答：请比对本页已列出的规格和实际需求。缺少信息时导购应继续询问，不能仅按品类推断兼容。
问：如何排查无法使用？
答：先核对型号、适用条件、供电或装配情况；避免强拆。仍无法解决时联系平台并提供型号 {sku} 和现象。
问：价格、库存和配送如何确定？
答：这些信息实时变化，必须以商城业务接口及订单页面为准，不采用本说明书作为实时依据。

## 演示售后
平台使用演示余额和模拟物流。未发货已支付订单可申请原路退回演示余额；其他状态以平台审核流程为准。
本说明书不宣称现实世界的生产、质保或商业承诺。
'''
            (manuals/f'{sku}.md').write_text(text,encoding='utf-8')
            html='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+escape(title)+'</title><style>body{max-width:760px;margin:48px auto;padding:0 24px;color:#253142;font:16px/1.9 system-ui}pre{white-space:pre-wrap;font:inherit}a{color:#2953ad}</style><a href="/products/'+str(ident)+'">返回商品</a><pre>'+escape(text)+'</pre></html>'
            (public_manuals/f'{sku}.html').write_text(html,encoding='utf-8')
            svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="400" viewBox="0 0 512 400"><title>{escape(title)}：品类示意图</title><rect width="512" height="400" fill="#edf0f2"/><ellipse cx="256" cy="328" rx="129" ry="12" fill="#d8dee3"/>{shape(category,COLORS[variant])}<text x="256" y="373" text-anchor="middle" font-family="sans-serif" font-size="12" fill="#77828c">{sku} · CONCEPT ILLUSTRATION</text></svg>'
            (assets/f'{sku}.svg').write_text(svg,encoding='utf-8')
    assert len(products)==200 and len({p['id'] for p in products})==200
    (ROOT/'data/products.json').write_text(json.dumps(products,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Generated 200 products, manuals and original concept illustrations.')

if __name__=='__main__': main()
