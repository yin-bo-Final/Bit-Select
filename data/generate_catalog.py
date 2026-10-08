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

def shape(category, color, name=None, variant=0):
    """Name-specific original product studies; no photos or manufacturer artwork.

    All 42 base products have distinct silhouettes. The five series use the
    catalogue colour plus small changes in proportions and edge radii.
    """
    if name is None:
        name = next(group[3][0] for group in GROUPS if group[0] == category)
    name = name.split(' · ')[0]
    prefix = f'p{name.encode("utf-8").hex()}-{variant}'

    def tint(value, amount):
        rgb = [int(value[i:i + 2], 16) for i in (1, 3, 5)]
        target = 255 if amount >= 0 else 0
        return '#' + ''.join(f'{round(channel + (target - channel) * abs(amount)):02x}' for channel in rgb)

    light, dark = tint(color, .27), tint(color, -.2)
    paint, metal = f'url(#{prefix}-body)', f'url(#{prefix}-metal)'
    fabric, perforation = f'url(#{prefix}-fabric)', f'url(#{prefix}-holes)'
    ink, pale = '#26313c', '#e7edf1'
    radius = 14 + variant * 2
    defs = f'''<defs>
      <linearGradient id="{prefix}-body" x1="0" y1="0" x2="1" y2="1"><stop stop-color="{light}"/><stop offset=".55" stop-color="{color}"/><stop offset="1" stop-color="{dark}"/></linearGradient>
      <linearGradient id="{prefix}-metal" x1="0" y1="0" x2="1" y2=".4"><stop stop-color="#aeb9c3"/><stop offset=".35" stop-color="#e1e7ec"/><stop offset=".65" stop-color="#c9d2db"/><stop offset="1" stop-color="#8c9aa7"/></linearGradient>
      <pattern id="{prefix}-fabric" width="5" height="5" patternUnits="userSpaceOnUse"><path d="M0 1h5M1 0v5" stroke="#edf1f4" stroke-opacity=".13" stroke-width=".65"/></pattern>
      <pattern id="{prefix}-holes" width="8" height="8" patternUnits="userSpaceOnUse"><circle cx="4" cy="4" r="1.35" fill="{ink}" fill-opacity=".45"/></pattern>
    </defs>'''

    def r(x, y, width, height, fill=paint, rx=radius, extra=''):
        return f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{rx}" fill="{fill}" {extra}/>'

    def p(d, fill=paint, stroke=None, width=2, extra=''):
        return f'<path d="{d}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"' if stroke else '') + f' {extra}/>'

    def line(d, stroke=ink, width=3, extra=''):
        return p(d, 'none', stroke, width, extra)

    def circle(x, y, rad, fill=paint, extra=''):
        return f'<circle cx="{x}" cy="{y}" r="{rad}" fill="{fill}" {extra}/>'

    def ellipse(x, y, rx, ry, fill=paint, extra=''):
        return f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" fill="{fill}" {extra}/>'

    if name == '氮化镓充电器':
        art = r(204, 54, 16, 60, metal, 4) + r(291, 54, 16, 60, metal, 4)
        art += r(168, 99, 177, 211, paint, 26 + variant * 2)
        art += p('M325 111q20 6 20 29v145q0 22-20 25Z', dark)
        art += r(189, 120, 120, 6, light, 3) + r(216, 168, 64, 21, ink, 8)
        art += r(225, 173, 46, 6, '#7e8a96', 3)
        art += f'<text x="250" y="258" text-anchor="middle" fill="{pale}" font-family="sans-serif" font-size="23" font-weight="600">{[20,30,45,65,100][variant]}W</text>'
    elif name == '编织充电线':
        coil = 'M192 126C80 136 88 283 206 292C345 311 397 169 312 129C214 84 139 167 183 229C229 288 344 222 307 170C276 127 217 165 238 206'
        art = line(coil, dark, 15) + line(coil, light, 3, 'stroke-dasharray="3 5"')
        art += line('M238 206q-4 24-24 26', color, 13) + line('M192 126Q198 102 220 88', color, 13)
        art += r(196, 63, 54, 54, paint, 12, 'transform="rotate(31 222 90)"')
        art += r(207, 41, 32, 29, metal, 7, 'transform="rotate(31 222 90)"')
        art += r(186, 224, 49, 64, paint, 10, 'transform="rotate(-22 210 245)"')
        art += r(195, 282, 31, 27, metal, 7, 'transform="rotate(-22 210 245)"')
    elif name == '桌面扩展坞':
        art = line('M378 175C435 167 416 110 373 107', dark, 12)
        art += r(325, 89, 62, 32, paint, 9) + r(307, 95, 25, 20, metal, 5)
        art += p('M115 154L351 136Q368 135 380 150L401 206L126 243L106 175Z', metal)
        art += p('M126 207L401 171V226Q400 239 385 242L135 275Q118 277 117 258V220Z', paint)
        for x, y in [(149, 229), (207, 221)]:
            art += r(x, y, 39, 16, ink, 3) + r(x + 5, y + 6, 29, 4, '#7891a0', 1)
        art += p('M284 217h54l-6 20h-42Z', ink) + line('M139 175L346 150', '#f2f5f7', 2)
    elif name == '轻薄移动电源':
        art = r(177, 83, 167, 237, paint, 19 + variant * 2)
        art += p('M323 88q21 5 21 23v185q0 18-21 24Z', dark)
        art += r(190, 88, 128, 18, dark, 8) + r(208, 91, 48, 9, ink, 4)
        art += r(274, 91, 25, 9, ink, 3) + line('M193 132v146', light, 3)
        for i in range(4): art += r(218 + i * 19, 258, 9, 4, pale if i < 3 else light, 2)
    elif name == '磁吸手机支架':
        art = ellipse(260, 303, 105, 21, metal) + ellipse(260, 299, 96, 17, paint)
        art += p('M247 162h27l18 125q1 13-16 13h-41q-8 0-7-9l19-129Z', metal)
        art += circle(256, 140, 80, paint) + circle(256, 140, 65, dark)
        art += circle(256, 140, 48, 'none', f'stroke="{light}" stroke-width="3"')
        art += line('M247 193h18', '#a8b4bf', 5)
    elif name == '无线充电座':
        art = ellipse(256, 241, 136, 65, dark) + r(120, 219, 272, 23, dark, 3)
        art += ellipse(256, 217, 136, 65, paint)
        art += ellipse(256, 215, 108, 47, 'none', f'stroke="{light}" stroke-width="2"')
        art += p('M265 188l-29 32h22l-10 23 30-32h-22Z', '#dfe6eb')
        art += line('M371 249q31 7 44-14', '#667583', 5) + r(242, 278, 27, 3, '#dbe4e9', 1)
    elif name == '读卡器':
        art = r(151, 146, 214, 109, metal, 17) + r(151, 220, 214, 35, paint, 12)
        art += r(352, 176, 54, 45, metal, 8) + r(393, 182, 10, 32, ink, 3)
        art += r(143, 174, 92, 30, ink, 3) + p('M101 124h69l24 24v84h-93Z', dark)
        art += r(116, 144, 57, 38, light, 4)
        for x in range(113, 179, 11): art += r(x, 218, 6, 16, '#b9a577', 1)
        art += line('M264 171h70M264 185h42', '#8494a2', 3)
    elif name == '旅行转换插座':
        art = p('M172 122l167-24 38 41v151l-168 27-37-38Z', dark)
        art += p('M172 122l38 34 167-17-38-41Z', light)
        art += r(205, 148, 166, 163, paint, 23)
        art += circle(289, 209, 45, tint(color, -.09))
        art += line('M267 186v27M310 186v27M288 224v21', ink, 10)
        art += r(244, 269, 87, 16, ink, 4) + r(251, 273, 72, 5, '#8c9eae', 2)
        art += r(161, 164, 16, 79, metal, 4)
    elif name == '桌面理线器':
        art = r(110, 218, 292, 69, paint, 28) + ellipse(256, 220, 144, 26, light)
        for x in [145, 199, 256, 312, 367]:
            art += ellipse(x, 223, 13, 17, dark)
            art += line(f'M{x} 227V162q0-25 17-42', '#c8d0d7', 9)
            art += line(f'M{x-2} 225V169', '#f5f7f8', 2)
        art += r(138, 270, 236, 5, dark, 2)
    elif name == '数码收纳包':
        art = line('M375 158q60-18 41 43q-16 37-42 25', color, 12)
        art += r(119, 117, 264, 190, paint, 35) + r(127, 124, 248, 173, fabric, 30)
        art += p('M132 170q109-34 237 0', 'none', dark, 5)
        art += line('M140 173q106-30 220 0', '#b9c4cd', 1.5, 'stroke-dasharray="2 3"')
        art += r(343, 168, 10, 27, metal, 3) + r(155, 226, 67, 32, dark, 5)
    elif name == '无线键盘':
        art = p('M100 143h316l23 154q2 17-17 19H88q-17-1-14-18Z', dark)
        art += p('M100 136h312l22 151q2 10-10 11H87q-10-1-8-12Z', paint)
        for row in range(5):
            y = 152 + row * 25
            for col in range(13):
                if row == 4 and 3 <= col <= 8: continue
                x = 100 + col * 24 - row * 2
                art += r(x, y, 19 + (1 if row > 2 else 0), 18, '#d3dbe2' if col != 12 else light, 3)
                art += line(f'M{x+5} {y+6}h4', '#81909d', 1)
        art += r(164, 252, 140, 18, '#c4cdd6', 4) + circle(385, 145, 2, '#cfdcd5')
    elif name == '静音鼠标':
        art = p('M180 206C180 142 205 103 256 103s76 39 76 103v39c0 54-26 78-76 78s-76-24-76-78Z', paint)
        art += p('M256 106v97h-73', 'none', dark, 2.5) + line('M259 203h69', dark, 2.5)
        art += r(246, 133, 20, 43, ink, 9) + r(251, 138, 10, 32, '#86939f', 4)
        art += line('M197 236q-2 49 21 59', light, 3)
    elif name == '电脑支架':
        art = p('M184 124h153l48 32-71 34-145-14Z', metal)
        art += p('M171 175h142l-23 91H151Z', paint) + p('M220 197h58l-11 46h-59Z', '#edf0f2')
        art += p('M150 265h140l89 36H171l-41-14Z', metal)
        art += r(141, 293, 248, 17, dark, 8) + line('M178 170h136', '#7f8d98', 6)
        art += line('M181 127h24M304 127h23', ink, 5)
    elif name == '办公台灯':
        art = ellipse(235, 307, 99, 19, paint) + ellipse(235, 302, 89, 15, light)
        art += line('M230 293V196L302 122', dark, 16) + line('M230 287V197L300 124', metal, 8)
        art += r(219, 89, 170, 41, paint, 18, 'transform="rotate(-12 305 110)"')
        art += line('M242 132L365 106', '#eef2ed', 6) + circle(232, 197, 13, paint)
        art += circle(238, 300, 9, 'none', f'stroke="{dark}" stroke-width="2"')
    elif name == '桌面收纳架':
        art = r(119, 126, 17, 187, paint, 4) + r(376, 126, 17, 187, paint, 4)
        art += p('M115 137L350 110l42 27H115Z', light) + r(115, 136, 280, 18, paint, 4)
        art += r(131, 224, 247, 15, paint, 3) + r(131, 298, 247, 17, paint, 3)
        art += r(244, 151, 14, 73, paint, 2) + r(319, 239, 12, 58, paint, 2)
        art += line('M142 170v42M270 252h35', light, 2)
    elif name == '显示器支架':
        art = r(116, 294, 73, 23, paint, 5) + p('M139 269h33v60h-54v-13h21Z', dark)
        art += line('M156 281V192L255 157L321 103', ink, 22)
        art += line('M157 280V193L256 158L322 104', metal, 14)
        art += circle(157, 194, 22, paint) + circle(256, 158, 23, paint)
        art += r(306, 68, 77, 77, paint, 10, 'transform="rotate(14 344 106)"')
        for x in [322, 365]:
            for y in [84, 128]: art += circle(x, y, 4, '#d8e1e8')
        art += line('M171 244h31l40-37', '#7d8b96', 5)
    elif name == '护腕鼠标垫':
        art = p('M165 115h185q22 0 29 24l32 139q6 33-26 35H132q-34-2-26-35l34-139q4-24 25-24Z', dark)
        art += p('M166 112h183q22 0 28 24l24 114H116l26-114q5-24 24-24Z', paint)
        art += p('M123 253q129-48 270 0l8 22q7 30-20 32H136q-30-3-23-28Z', light)
        art += p('M132 257q125-38 250 0', 'none', tint(color, .46), 2)
    elif name == '无线耳机':
        art = r(151, 180, 214, 136, paint, 52) + ellipse(258, 185, 104, 42, dark)
        art += p('M156 178v-40q0-44 46-46h111q47 0 47 46v40q-99-29-204 0Z', paint)
        art += p('M173 158v-23q0-25 31-25h106q31 0 31 25v23q-86-17-168 0Z', dark)
        for x in [216, 298]:
            art += r(x - 11, 152, 23, 62, pale, 10) + ellipse(x - 1, 150, 25, 18, '#d6e0e7')
            art += ellipse(x - 4, 151, 9, 7, '#697985')
        art += line('M169 236q89 17 177 0', light, 2) + circle(258, 278, 3, '#c0d1c8')
    elif name == '桌面蓝牙音箱':
        art = r(147, 111, 218, 210, paint, 37)
        art += r(161, 144, 190, 161, dark, 23) + r(161, 144, 190, 161, perforation, 23)
        art += ellipse(256, 112, 86, 12, light)
        art += line('M216 114h12M222 108v12M252 114h12', dark, 2.5) + circle(293, 114, 5, 'none', f'stroke="{dark}" stroke-width="2"')
        art += r(223, 281, 67, 13, paint, 3)
    elif name == '开放式耳机':
        art = line('M195 263C95 238 108 84 182 90C220 93 226 148 201 180', dark, 19)
        art += line('M321 263C421 238 408 84 334 90C296 93 290 148 315 180', dark, 19)
        art += line('M196 259C111 239 121 100 180 103', light, 5)
        art += line('M320 259C405 239 395 100 336 103', light, 5)
        art += r(172, 172, 57, 103, paint, 25, 'transform="rotate(22 200 220)"')
        art += r(287, 172, 57, 103, paint, 25, 'transform="rotate(-22 316 220)"')
        art += ellipse(202, 194, 17, 8, ink) + ellipse(314, 194, 17, 8, ink)
    elif name == '智能插座':
        art = r(159, 149, 27, 25, metal, 3) + r(159, 235, 27, 25, metal, 3)
        art += p('M176 114l119-14 61 28v166l-124 19-56-27Z', dark)
        art += r(209, 120, 152, 193, paint, 29)
        art += circle(285, 206, 48, light) + line('M264 181v24M306 181v24M285 222v20', ink, 9)
        art += circle(285, 282, 10, 'none', f'stroke="{pale}" stroke-width="2"') + line('M285 271v9', pale, 2)
    elif name == '温湿度计':
        art = p('M307 254l37 61h-54l-18-52Z', dark) + r(151, 110, 216, 180, paint, 28)
        art += r(172, 130, 175, 116, '#dce5e4', 13)
        art += '<text x="259" y="188" text-anchor="middle" font-family="sans-serif" font-size="44" font-weight="500" fill="#46545b">24.6<tspan font-size="18">°C</tspan></text>'
        art += '<text x="260" y="223" text-anchor="middle" font-family="sans-serif" font-size="19" fill="#637378">48% RH</text>'
        art += circle(258, 269, 5, light)
    elif name == '桌面循环风扇':
        art = ellipse(256, 310, 89, 15, paint) + r(242, 220, 29, 82, metal, 10)
        art += circle(256, 158, 105, paint) + circle(256, 158, 88, '#dce3e8')
        art += p('M256 158C160 123 210 62 256 158C291 60 352 118 256 158C340 204 270 260 256 158', light)
        for radius2 in [34, 58, 80]: art += circle(256, 158, radius2, 'none', 'stroke="#8b9aa6" stroke-width="1.5"')
        for angle in range(0, 360, 30): art += line('M256 73V243', '#8b9aa6', 1.3, f'transform="rotate({angle} 256 158)"')
        art += circle(256, 158, 17, paint) + r(219, 303, 75, 5, light, 2)
    elif name == '冷雾加湿器':
        art = r(173, 151, 170, 150, '#d2e0e5', 24) + ellipse(258, 153, 85, 28, '#e4ebee')
        art += r(174, 243, 168, 72, paint, 21) + ellipse(258, 246, 84, 19, light)
        art += ellipse(258, 142, 91, 29, paint) + ellipse(258, 137, 79, 21, light)
        art += r(239, 123, 40, 17, dark, 8) + line('M203 177v51', '#f3f6f7', 7)
        art += line('M238 106q-21-18 0-35M260 105q22-20 0-45M281 108q18-18 2-30', '#b6c6d0', 3)
        art += circle(258, 283, 10, 'none', 'stroke="#ccd9e0" stroke-width="2"')
    elif name == '手持吸尘器':
        art = p('M281 202l32 3-14 103q-3 13-18 9l-28-7q-10-4-5-16Z', paint)
        art += r(229, 136, 151, 91, paint, 34) + ellipse(364, 181, 20, 35, dark)
        art += r(144, 146, 117, 71, '#c7d7e0', 15) + r(165, 159, 88, 47, '#a2b5c2', 13)
        art += p('M154 154H88l-25 39 87 15Z', metal) + r(68, 181, 60, 19, ink, 4)
        for x in range(312, 355, 9): art += line(f'M{x} 160v42', dark, 3)
        art += r(279, 237, 17, 26, dark, 7)
    elif name == '电动牙刷':
        art = ellipse(256, 322, 55, 11, metal) + r(229, 140, 54, 181, paint, 25)
        art += r(246, 90, 20, 76, light, 9) + r(237, 48, 39, 62, pale, 15)
        for y in range(55, 102, 8): art += line(f'M242 {y}h28', '#8aabb8', 4)
        art += circle(256, 199, 10, 'none', f'stroke="{pale}" stroke-width="2"') + line('M256 188v10', pale, 2)
        art += r(250, 246, 12, 28, dark, 5) + line('M237 163v113', light, 3)
    elif name == '便携吹风机':
        art = p('M263 206h53l-10 97q-2 20-20 15l-24-6q-13-4-9-17Z', paint)
        art += r(164, 111, 194, 118, paint, 48) + ellipse(352, 170, 31, 54, dark)
        art += ellipse(180, 170, 47, 55, metal) + ellipse(180, 170, 33, 41, ink)
        art += ellipse(180, 170, 29, 36, perforation) + p('M151 145l-43 8v37l43 11Z', dark)
        art += r(275, 240, 17, 35, ink, 7) + line('M281 319q-8 13 17 17', '#7e8c97', 5)
    elif name == '衣物毛球修剪器':
        art = r(230, 207, 58, 107, paint, 24) + r(194, 95, 133, 153, paint, 62)
        art += circle(255, 163, 62, metal) + circle(255, 163, 51, '#dce3e9')
        art += circle(255, 163, 49, perforation) + circle(255, 163, 13, metal)
        art += r(246, 263, 25, 15, dark, 7) + line('M239 294h38', light, 3)
    elif name == '除螨清洁机':
        art = p('M199 176q-2-76 53-76h66q39 0 40 37v54h-25v-39q0-26-26-26h-47q-32 0-30 50Z', paint)
        art += p('M146 242l51-84h138l49 84Z', metal) + r(124, 228, 279, 86, paint, 27)
        art += r(213, 158, 111, 97, '#c3d3dd', 25) + r(227, 175, 82, 64, '#92a8b6', 18)
        art += r(145, 285, 236, 14, dark, 7) + r(155, 287, 213, 6, '#899cab', 3)
        art += circle(349, 258, 10, light)
    elif name == '不锈钢保温杯':
        art = r(191, 108, 134, 212, paint, 30) + r(189, 84, 138, 53, dark, 18)
        art += ellipse(258, 88, 62, 13, light) + r(192, 130, 132, 10, metal, 3)
        art += line('M211 153v125', light, 9) + line('M232 152v97', tint(color, .15), 2)
        art += ellipse(258, 308, 52, 7, dark)
    elif name == '轻量炒锅':
        art = line('M173 219L72 159', ink, 26) + line('M167 211L91 167', color, 20)
        art += p('M154 218q12 100 136 100q103 0 123-100Z', paint)
        art += ellipse(284, 214, 135, 60, metal) + ellipse(284, 211, 121, 49, ink)
        art += ellipse(284, 215, 99, 33, '#465560') + p('M390 179q50-11 49 22q0 19-32 21', 'none', dark, 13)
        art += line('M184 263q52 36 125 28', light, 3)
    elif name == '耐热玻璃饭盒':
        art = r(133, 164, 253, 135, '#bbced9', 29) + r(149, 189, 221, 91, '#e5edf1', 20)
        art += p('M148 196v62q0 12 14 12h166', 'none', '#f7f9fa', 5)
        art += r(123, 140, 274, 67, paint, 28) + r(140, 148, 240, 39, '#dbe5eb', 18)
        art += r(143, 191, 56, 20, dark, 5) + r(319, 191, 56, 20, dark, 5)
        art += line('M158 165h184', '#f3f6f8', 3)
    elif name == '餐具套装':
        art = r(155, 174, 17, 147, metal, 8) + ellipse(164, 129, 34, 54, metal)
        art += ellipse(157, 122, 19, 37, '#e0e7ed')
        art += r(247, 169, 17, 152, metal, 8)
        art += p('M225 74h8v67h10V74h8v67h10V74h8v67h10V74h8v86q0 23-31 23t-31-23Z', metal)
        art += p('M343 78q34 43 31 111l-16 9v113q0 11-10 11t-10-11V82q0-8 5-4Z', metal)
        art += r(153, 233, 21, 89, paint, 8) + r(245, 233, 21, 89, paint, 8) + r(336, 232, 24, 90, paint, 8)
    elif name == '厨房收纳架':
        art = line('M142 305V119q0-17 18-17h189q19 0 19 17v186', paint, 13)
        art += line('M163 120v183M348 120v183', metal, 6)
        for y in [177, 271]:
            art += r(126, y, 260, 14, paint, 5)
            art += line(f'M133 {y-22}h247M135 {y-22}v24M378 {y-22}v24', metal, 4)
            for x in range(147, 377, 23): art += line(f'M{x} {y-22}v23', metal, 3)
        art += r(126, 304, 39, 13, dark, 5) + r(349, 304, 39, 13, dark, 5)
    elif name == '手冲咖啡壶':
        art = p('M325 150q76-7 70 55q-4 52-74 43', 'none', dark, 18)
        art += p('M193 219C161 221 145 192 141 162C139 148 128 137 118 144L111 135C135 116 155 136 160 160C164 185 175 196 199 197Z', metal)
        art += p('M209 136h99q35 21 39 100q1 76-94 77q-91-1-91-76q6-80 47-101Z', paint)
        art += ellipse(258, 143, 64, 19, metal) + ellipse(258, 136, 52, 13, paint)
        art += r(244, 106, 28, 31, dark, 10) + ellipse(258, 108, 18, 7, light)
        art += line('M199 185q-20 67 18 92', light, 5)
    elif name == '切菜板':
        art = r(155, 76, 213, 253, dark, 24, 'transform="rotate(7 261 202)"')
        art += r(148, 70, 213, 253, paint, 24, 'transform="rotate(7 254 196)"')
        art += r(218, 91, 65, 19, '#edf0f2', 9, 'transform="rotate(7 250 101)"')
        for x in [178, 201, 237, 280, 323]: art += line(f'M{x} 143q-11 48 0 75t-3 79', light, 1.4, 'stroke-opacity=".55"')
    elif name == '旅行双肩包':
        art = line('M204 107V90q0-45 53-45t53 45v17', dark, 14)
        art += line('M179 142q-50 53-29 137M336 142q50 53 29 137', dark, 17)
        art += r(153, 91, 206, 235, paint, 50) + r(160, 96, 192, 225, fabric, 46)
        art += p('M171 158q85-42 170 0', 'none', dark, 5)
        art += r(176, 215, 162, 89, tint(color, .11), 21) + line('M190 234h133', dark, 4)
        art += r(310, 230, 10, 25, metal, 3) + line('M167 191v83M346 191v83', light, 2)
    elif name == '折叠收纳箱':
        art = p('M131 159l224-27 50 47-226 28Z', light)
        art += p('M132 159l47 47v112l-47-45Z', dark)
        art += p('M179 199l226-23v114l-226 28Z', paint)
        art += p('M188 207l207-22v98l-207 22Z', fabric)
        art += r(259, 224, 66, 25, dark, 7) + r(270, 230, 44, 10, '#edf0f2', 4)
        art += line('M188 271l208-23M188 287l207-23M188 218l207-23', light, 2)
        art += p('M123 145l229-29 57 47-232 29Z', paint) + line('M135 144l215-25', light, 2)
    elif name == '记忆棉枕':
        art = p('M94 221q15-81 97-89q66 29 111 0q81-3 116 77l-7 64q-152 54-310 0Z', paint)
        art += p('M96 222q31-68 96-57q66 29 112 1q71-15 112 43q-89 81-207 54Z', tint(color, .64))
        art += p('M104 261q156 49 301-7', 'none', tint(color, .37), 2, 'stroke-dasharray="4 4"')
        art += p('M120 218q35-34 73-29q71 32 118-1q45-4 74 24', 'none', tint(color, .8), 3)
    elif name == '轻便雨伞':
        art = line('M256 91v208q0 36-31 31q-20-2-20-23', metal, 8)
        art += line('M256 275v23q0 32-28 27q-19-3-19-20', dark, 13)
        art += p('M100 196Q117 91 254 74Q389 91 412 196Q375 169 341 196Q295 167 257 196Q209 164 169 196Q135 171 100 196Z', paint)
        art += p('M254 75Q182 95 169 196M254 75Q329 91 341 196M254 75v121', 'none', light, 2)
        art += line('M254 62v12', dark, 7)
    elif name == '速干浴巾':
        art = r(105, 199, 301, 109, paint, 21) + r(112, 168, 291, 97, tint(color, .29), 20)
        art += p('M118 174q101-20 272 0v55q-147-14-275 0Z', tint(color, .43))
        art += r(121, 178, 269, 66, fabric, 10)
        art += line('M126 247h259M125 281h258', dark, 2)
        for x in range(132, 387, 10): art += line(f'M{x} 289v13', light, 1.4)
    elif name == '旅行洗漱包':
        art = line('M376 158q52-7 43 38q-5 26-42 34', dark, 14)
        art += p('M127 160q7-39 47-43h156q43 4 53 44l7 121q-1 26-28 30H147q-27-1-29-28Z', paint)
        art += p('M131 164q130-27 247 0l-5-23q-10-21-44-23H175q-33 4-44 23Z', light)
        art += r(131, 174, 246, 125, fabric, 18) + line('M145 163q112-22 217 0', ink, 4)
        art += r(342, 159, 10, 28, metal, 3) + r(191, 216, 74, 35, dark, 5)
        art += line('M137 186v97q1 13 14 13h205', light, 2, 'stroke-dasharray="4 4"')
    else:
        raise ValueError(f'No original illustration defined for {name}')

    sx, sy = .94 + variant * .025, .96 + variant * .012
    return defs + f'<g transform="translate(256 200) scale({sx:.3f} {sy:.3f}) translate(-256 -200)">{art}</g>'

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
            html='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+escape(title)+'</title><link rel="stylesheet" href="/manuals/manual.css"><script src="/manuals/manual.js"></script><a href="/products/'+str(ident)+'">返回商品</a><pre>'+escape(text)+'</pre></html>'
            (public_manuals/f'{sku}.html').write_text(html,encoding='utf-8')
            svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="400" viewBox="0 0 512 400"><title>{escape(title)}：原创商品概念图</title><rect width="512" height="400" fill="#edf0f2"/><ellipse cx="256" cy="328" rx="129" ry="12" fill="#d8dee3"/>{shape(category,COLORS[variant],name,variant)}<text x="256" y="373" text-anchor="middle" font-family="sans-serif" font-size="12" fill="#77828c">{sku} · CONCEPT ILLUSTRATION</text></svg>'
            (assets/f'{sku}.svg').write_text(svg,encoding='utf-8')
    assert len(products)==200 and len({p['id'] for p in products})==200
    (ROOT/'data/products.json').write_text(json.dumps(products,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Generated 200 products, manuals and original concept illustrations.')

if __name__=='__main__': main()
